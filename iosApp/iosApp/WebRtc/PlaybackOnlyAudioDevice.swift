import AudioToolbox
import AVFoundation
import Shared
import WebRTC

/// The audio device libwebrtc plays remote audio through: a RemoteIO unit with only its output
/// turned on.
///
/// libwebrtc's own iOS device is built for calls. It opens a VoiceProcessingIO unit with the
/// input bus enabled as soon as there is anything to play, even on a receive-only peer, and
/// opening an input is a microphone access. The app has no microphone usage description
/// (nothing in it records), so iOS kills the process the first time a stream with an audio
/// track plays: a Nest camera opened with sound did exactly that. This device never creates an
/// input, so the microphone is never touched, and recording is refused outright.
///
/// Samples are 16-bit mono at 48 kHz, the shape libwebrtc's device module asks for; RemoteIO
/// converts to whatever the route plays.
///
/// The audio session is active only while there is sound to play: it is activated as playout
/// starts, and released a moment after the last stop, telling other apps (music a camera's
/// sound paused) they may resume. The release waits for the app's AVPlayers too, which share
/// the session (`IosAudioSessionPlayers`): deactivating stops whatever runs in it.
///
/// Everything but the render callback runs on the device module's thread.
final class PlaybackOnlyAudioDevice: NSObject, RTCAudioDevice {

    private static let sampleRate = 48_000.0

    /// How long the session outlives a stop, so moving from one camera to the next doesn't
    /// hand the audio to another app and take it straight back.
    private static let sessionReleaseDelay: TimeInterval = 1.5

    private var delegate: RTCAudioDeviceDelegate?
    private var unit: AudioUnit?
    private var context: PlayoutContext?
    private var interruptionObserver: NSObjectProtocol?
    private var sessionActive = false
    /// Bumped by every start and stop, so a release scheduled before either is dropped.
    private var releaseGeneration = 0

    private(set) var isInitialized = false
    private(set) var isPlayoutInitialized = false
    private(set) var isPlaying = false
    let isRecordingInitialized = false
    let isRecording = false

    var deviceInputSampleRate: Double { Self.sampleRate }
    var inputIOBufferDuration: TimeInterval { AVAudioSession.sharedInstance().ioBufferDuration }
    var inputNumberOfChannels: Int { 1 }
    var inputLatency: TimeInterval { 0 }
    var deviceOutputSampleRate: Double { Self.sampleRate }
    var outputIOBufferDuration: TimeInterval { AVAudioSession.sharedInstance().ioBufferDuration }
    var outputNumberOfChannels: Int { 1 }
    var outputLatency: TimeInterval { AVAudioSession.sharedInstance().outputLatency }

    func initialize(with delegate: RTCAudioDeviceDelegate) -> Bool {
        self.delegate = delegate
        isInitialized = true
        return true
    }

    func terminateDevice() -> Bool {
        _ = stopPlayout()
        disposeUnit()
        delegate = nil
        isInitialized = false
        return true
    }

    func initializePlayout() -> Bool {
        guard isInitialized, let delegate else { return false }
        if isPlayoutInitialized { return true }
        let session = AVAudioSession.sharedInstance()
        do {
            // The category libwebrtc's own session was configured with before: plain playback,
            // out of the speaker, through the ringer switch, like any video player.
            try session.setCategory(.playback, mode: .moviePlayback, options: [])
        } catch {
            NSLog("HomeSafeWebRtc: audio session refused playback: \(error)")
            return false
        }
        let context = PlayoutContext(getPlayoutData: delegate.getPlayoutData)
        guard let unit = makeOutputUnit(context: context) else { return false }
        self.unit = unit
        self.context = context
        observeInterruptions()
        isPlayoutInitialized = true
        return true
    }

    func startPlayout() -> Bool {
        guard isPlayoutInitialized, let unit else { return false }
        if isPlaying { return true }
        releaseGeneration += 1
        // Every start, not only the first: the session was released after the last stop, or
        // ended by a call that came while nothing played.
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            sessionActive = true
        } catch {
            NSLog("HomeSafeWebRtc: audio session would not activate: \(error)")
            return false
        }
        let status = AudioOutputUnitStart(unit)
        guard status == noErr else {
            NSLog("HomeSafeWebRtc: audio unit would not start: \(status)")
            return false
        }
        isPlaying = true
        return true
    }

    func stopPlayout() -> Bool {
        guard isPlaying else { return true }
        if let unit { AudioOutputUnitStop(unit) }
        isPlaying = false
        // The next start may render on another thread; the module's playout thread check is
        // reset by this, as after an interruption.
        delegate?.notifyAudioOutputInterrupted()
        scheduleSessionRelease()
        return true
    }

    /// Releases the session once playout has stayed stopped for `sessionReleaseDelay` and no
    /// AVPlayer in the app is playing (asked on the main thread, where the players live).
    private func scheduleSessionRelease() {
        releaseGeneration += 1
        let generation = releaseGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.sessionReleaseDelay) { [weak self] in
            guard let self, !IosAudioSessionPlayers.shared.anyPlaying else { return }
            self.delegate?.dispatchAsync {
                guard generation == self.releaseGeneration, !self.isPlaying, self.sessionActive else { return }
                do {
                    try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
                    self.sessionActive = false
                } catch {
                    NSLog("HomeSafeWebRtc: audio session would not deactivate: \(error)")
                }
            }
        }
    }

    // Nothing here records: refusing is what keeps the microphone untouched.
    func initializeRecording() -> Bool { false }
    func startRecording() -> Bool { false }
    func stopRecording() -> Bool { true }

    private func makeOutputUnit(context: PlayoutContext) -> AudioUnit? {
        var description = AudioComponentDescription(
            componentType: kAudioUnitType_Output,
            componentSubType: kAudioUnitSubType_RemoteIO,
            componentManufacturer: kAudioUnitManufacturer_Apple,
            componentFlags: 0,
            componentFlagsMask: 0
        )
        guard let component = AudioComponentFindNext(nil, &description) else { return nil }
        var created: AudioUnit?
        guard AudioComponentInstanceNew(component, &created) == noErr, let unit = created else { return nil }

        let outputBus: AudioUnitElement = 0
        let inputBus: AudioUnitElement = 1
        var off: UInt32 = 0
        var on: UInt32 = 1
        var format = AudioStreamBasicDescription(
            mSampleRate: Self.sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: 2,
            mFramesPerPacket: 1,
            mBytesPerFrame: 2,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 16,
            mReserved: 0
        )
        var callback = AURenderCallbackStruct(
            inputProc: renderPlayout,
            inputProcRefCon: Unmanaged.passUnretained(context).toOpaque()
        )
        let flagSize = UInt32(MemoryLayout<UInt32>.size)
        let steps: [OSStatus] = [
            // RemoteIO's input is off by default; said again here because it is the whole point.
            AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input, inputBus, &off, flagSize),
            AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Output, outputBus, &on, flagSize),
            AudioUnitSetProperty(
                unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, outputBus,
                &format, UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
            ),
            AudioUnitSetProperty(
                unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, outputBus,
                &callback, UInt32(MemoryLayout<AURenderCallbackStruct>.size)
            ),
            AudioUnitInitialize(unit),
        ]
        if let failed = steps.first(where: { $0 != noErr }) {
            NSLog("HomeSafeWebRtc: audio unit setup failed: \(failed)")
            AudioComponentInstanceDispose(unit)
            return nil
        }
        return unit
    }

    private func disposeUnit() {
        if let observer = interruptionObserver {
            NotificationCenter.default.removeObserver(observer)
            interruptionObserver = nil
        }
        if let unit {
            AudioUnitUninitialize(unit)
            AudioComponentInstanceDispose(unit)
        }
        unit = nil
        context = nil
        isPlayoutInitialized = false
    }

    /// A call or Siri stops the unit; it is started again once the interruption ends. The
    /// render thread can change across the restart, which the device module is told about.
    private func observeInterruptions() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: nil
        ) { [weak self] note in
            guard let self, let delegate = self.delegate,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            delegate.dispatchAsync {
                // The system ends the session for a call whether or not anything was playing.
                if type == .began { self.sessionActive = false }
                guard self.isPlaying, let unit = self.unit else { return }
                switch type {
                case .began:
                    AudioOutputUnitStop(unit)
                    delegate.notifyAudioOutputInterrupted()
                case .ended:
                    guard (try? AVAudioSession.sharedInstance().setActive(true)) != nil else { return }
                    self.sessionActive = true
                    AudioOutputUnitStart(unit)
                @unknown default:
                    break
                }
            }
        }
    }
}

/// What the render thread reads: only the device module's pull block, fixed for the unit's life.
private final class PlayoutContext {
    let getPlayoutData: RTCAudioDeviceGetPlayoutDataBlock

    init(getPlayoutData: @escaping RTCAudioDeviceGetPlayoutDataBlock) {
        self.getPlayoutData = getPlayoutData
    }
}

private let renderPlayout: AURenderCallback = { refCon, flags, timestamp, bus, frames, data in
    guard let data else { return noErr }
    let context = Unmanaged<PlayoutContext>.fromOpaque(refCon).takeUnretainedValue()
    return context.getPlayoutData(flags, timestamp, Int(bus), frames, data)
}
