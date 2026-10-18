import AudioToolbox
import AVFoundation
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
final class PlaybackOnlyAudioDevice: NSObject, RTCAudioDevice {

    private static let sampleRate = 48_000.0

    private var delegate: RTCAudioDeviceDelegate?
    private var unit: AudioUnit?
    private var context: PlayoutContext?
    private var interruptionObserver: NSObjectProtocol?

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
            try session.setActive(true)
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
        let status = AudioOutputUnitStart(unit)
        guard status == noErr else {
            NSLog("HomeSafeWebRtc: audio unit would not start: \(status)")
            return false
        }
        isPlaying = true
        return true
    }

    func stopPlayout() -> Bool {
        if isPlaying, let unit { AudioOutputUnitStop(unit) }
        isPlaying = false
        return true
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
                guard self.isPlaying, let unit = self.unit else { return }
                switch type {
                case .began:
                    AudioOutputUnitStop(unit)
                    delegate.notifyAudioOutputInterrupted()
                case .ended:
                    try? AVAudioSession.sharedInstance().setActive(true)
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
