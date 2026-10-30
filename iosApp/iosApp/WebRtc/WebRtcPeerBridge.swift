import Foundation
import UIKit
import AVFoundation
import WebRTC
import Shared

/// The iOS WebRTC engine behind the shared module's `IosWebRtcPeer` contract (see
/// `WebRtcPeer.ios.kt`). One `RTCPeerConnectionFactory` per process; each peer is receive-only,
/// offers host candidates only (the server is reached over the LAN or Tailscale, so there is
/// no STUN or TURN), and draws its remote video into whatever container views the holder
/// attaches, stretched edge to edge like every other live surface in the app.
///
/// libwebrtc calls its delegates on its own threads; every callback into Kotlin is hopped to
/// the main queue first, which is where all of the shared player code runs.
///
/// "Video disabled" detaches the renderers from the track rather than disabling the track:
/// libwebrtc keeps decoding a remote track either way, and a disabled remote track hands its
/// renderers black frames — which would paint over the last good picture the moment a viewer
/// pressed pause. Detached renderers keep what they last drew, and re-attaching shows the very
/// next decoded frame, which is what lets the shared holder keep a peer on standby and swap it
/// back in without a new join.
///
/// So that a peer nobody draws stops decoding, the shared adapter also asks it to stop receiving
/// its video once it has been disabled for a few seconds (`setVideoReceiving`). This build of
/// libwebrtc has no switch for a track's receive stream (Android's `setShouldReceive`), so the
/// peer renegotiates the video transceiver to inactive, which stops the stream, decoder and all.
/// Only locally: the server has already answered, and an answer is all the renegotiation
/// needs, so the peer applies the server's own answer with its video section marked inactive.
/// The server goes on sending, and nothing it sees changes. Receiving again is the same with the
/// answer as it came, and the picture moves again at the stream's next keyframe.
final class WebRtcPeerBridgeFactory: NSObject, IosWebRtcPeerFactory {

    static let shared = WebRtcPeerBridgeFactory()

    private lazy var factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        // Playback only, through our own audio device. libwebrtc's default device is built for
        // calls and opens the microphone as soon as a stream with sound plays, which iOS kills
        // the app for (it has no microphone usage description; nothing here ever records).
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory(),
            audioDevice: PlaybackOnlyAudioDevice()
        )
    }()

    func create(audio: Bool, listener: IosWebRtcPeerListener) -> IosWebRtcPeer {
        WebRtcPeerBridge(factory: factory, audio: audio, listener: listener)
    }
}

final class WebRtcPeerBridge: NSObject, IosWebRtcPeer, RTCPeerConnectionDelegate {

    private let listener: IosWebRtcPeerListener
    private var connection: RTCPeerConnection!
    private var videoTrack: RTCVideoTrack?
    private var audioTrack: RTCAudioTrack?
    private var renderers: [ObjectIdentifier: (container: UIView, view: RTCMTLVideoView, delegate: FirstFrameDelegate)] = [:]
    private let frameCounter = FrameCounter()
    private var offerCompletion: ((String?, String?) -> Void)?
    private var videoEnabled = true
    private var muted = true
    private var closed = false

    private var videoTransceiver: RTCRtpTransceiver?

    /// The server's answer as it came, which every local renegotiation answers itself with.
    private var answerSdp: String?

    /// Whether the video should be received, whether it is, and whether a renegotiation is on its way there.
    private var receiveWanted = true
    private var receiving = true
    private var renegotiating = false

    /// Bumped by each resume, so only the latest one's watch can report the video missing.
    private var resumeWatch = 0

    /// Tells one peer's log lines from another's.
    private lazy var logId = String(UInt(bitPattern: ObjectIdentifier(self).hashValue) & 0xFFFF, radix: 16)

    init(factory: RTCPeerConnectionFactory, audio: Bool, listener: IosWebRtcPeerListener) {
        self.listener = listener
        super.init()
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.continualGatheringPolicy = .gatherOnce
        config.iceServers = []
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        connection = factory.peerConnection(with: config, constraints: constraints, delegate: self)
        let recvOnly = RTCRtpTransceiverInit()
        recvOnly.direction = .recvOnly
        videoTransceiver = connection.addTransceiver(of: .video, init: recvOnly)
        if audio { connection.addTransceiver(of: .audio, init: recvOnly) }
        frameCounter.onFirstFrame = { [weak self] in
            DispatchQueue.main.async { self?.listener.onFirstFrame() }
        }
    }

    // MARK: IosWebRtcPeer

    func createOffer(completion: @escaping (String?, String?) -> Void) {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        connection.offer(for: constraints) { [weak self] sdp, error in
            guard let self else { return }
            guard let sdp else {
                DispatchQueue.main.async { completion(nil, error?.localizedDescription ?? "createOffer failed") }
                return
            }
            self.connection.setLocalDescription(sdp) { error in
                if let error {
                    DispatchQueue.main.async { completion(nil, error.localizedDescription) }
                    return
                }
                // No trickle: the offer goes out once every host candidate is in it. Gathering
                // may already be complete by the time the local description is set.
                DispatchQueue.main.async {
                    if self.connection.iceGatheringState == .complete {
                        completion(self.connection.localDescription?.sdp, nil)
                    } else {
                        self.offerCompletion = completion
                    }
                }
            }
        }
    }

    func setAnswer(sdp: String, completion: @escaping (String?) -> Void) {
        connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self] error in
            DispatchQueue.main.async {
                if error == nil { self?.answerSdp = sdp }
                completion(error?.localizedDescription)
            }
        }
    }

    func attachRenderer(container: UIView, onFirstFrame: @escaping () -> Void) {
        guard !closed else { return }
        let key = ObjectIdentifier(container)
        if let existing = renderers[key] {
            existing.delegate.onFirstFrame = onFirstFrame
            return
        }
        let view = RTCMTLVideoView(frame: container.bounds)
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.videoContentMode = .scaleToFill
        view.backgroundColor = .clear
        let delegate = FirstFrameDelegate(onFirstFrame: onFirstFrame)
        view.delegate = delegate
        container.addSubview(view)
        renderers[key] = (container, view, delegate)
        if videoEnabled { videoTrack?.add(view) }
    }

    func detachRenderer(container: UIView) {
        guard let entry = renderers.removeValue(forKey: ObjectIdentifier(container)) else { return }
        videoTrack?.remove(entry.view)
        entry.view.removeFromSuperview()
    }

    func setVideoEnabled(enabled: Bool) {
        guard videoEnabled != enabled else { return }
        videoEnabled = enabled
        guard let track = videoTrack else { return }
        for entry in renderers.values {
            if enabled { track.add(entry.view) } else { track.remove(entry.view) }
        }
    }

    func setVideoReceiving(receiving: Bool) {
        receiveWanted = receiving
        renegotiateReceiving()
    }

    /// Renegotiates the video transceiver towards `receiveWanted`, one renegotiation at a time; see the class doc.
    private func renegotiateReceiving() {
        guard !closed, !renegotiating, receiving != receiveWanted,
              let transceiver = videoTransceiver, let answer = answerSdp else { return }
        let receive = receiveWanted
        guard let remoteSdp = receive ? answer : Self.withVideoInactive(answer) else {
            // An answer with no direction to turn: leave the video running, as it always was.
            NSLog("HomeSafeLive: no video direction in the answer; video keeps decoding")
            answerSdp = nil
            return
        }
        renegotiating = true
        var directionError: NSError?
        transceiver.setDirection(receive ? .recvOnly : .inactive, error: &directionError)
        if let directionError {
            finishRenegotiating(receive: receive, error: directionError.localizedDescription, offerApplied: false)
            return
        }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        connection.offer(for: constraints) { [weak self] sdp, error in
            guard let self else { return }
            guard let sdp else {
                DispatchQueue.main.async {
                    self.finishRenegotiating(receive: receive, error: error?.localizedDescription ?? "offer failed", offerApplied: false)
                }
                return
            }
            self.connection.setLocalDescription(sdp) { error in
                if let error {
                    DispatchQueue.main.async {
                        self.finishRenegotiating(receive: receive, error: error.localizedDescription, offerApplied: false)
                    }
                    return
                }
                self.connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: remoteSdp)) { error in
                    DispatchQueue.main.async {
                        self.finishRenegotiating(receive: receive, error: error?.localizedDescription, offerApplied: true)
                    }
                }
            }
        }
    }

    private func finishRenegotiating(receive: Bool, error: String?, offerApplied: Bool) {
        renegotiating = false
        guard !closed else { return }
        guard let error else {
            receiving = receive
            NSLog("HomeSafeLive: peer %@ video receive %@", logId, receive ? "resumed" : "paused")
            if receive { watchForFrameAfterResume() }
            renegotiateReceiving()
            return
        }
        NSLog("HomeSafeLive: video receive renegotiation failed: %@", error)
        if receive {
            // The video would stay still for good: report the peer lost, and the holder joins again.
            listener.onFailed(reason: "video could not be resumed: \(error)")
            return
        }
        // Stopping is only a saving: put things back as they were and leave the video running.
        answerSdp = nil
        receiveWanted = true
        var ignored: NSError?
        videoTransceiver?.setDirection(.recvOnly, error: &ignored)
        if offerApplied {
            connection.setLocalDescription(RTCSessionDescription(type: .rollback, sdp: "")) { _ in }
        }
    }

    /// A resumed stream moves again at its next keyframe, a second or two at most. One with nothing
    /// by `resumeFrameTimeout` is reported failed, so the holder joins again rather than leaving the
    /// card still (or black) for good.
    private func watchForFrameAfterResume() {
        resumeWatch += 1
        let watch = resumeWatch
        frameCounter.reportNextFrame(as: "peer \(logId) video moving again")
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.resumeFrameTimeout) { [weak self] in
            guard let self, !self.closed, watch == self.resumeWatch, self.receiving, self.receiveWanted,
                  self.frameCounter.isAwaitingFrame else { return }
            NSLog("HomeSafeLive: peer %@ has had no video since resuming", self.logId)
            self.listener.onFailed(reason: "no video after resuming")
        }
    }

    private static let resumeFrameTimeout: TimeInterval = 8

    /// `sdp` with its video section's direction turned to inactive, or nil when it states none.
    static func withVideoInactive(_ sdp: String) -> String? {
        var inVideo = false
        var turned = false
        let lines = sdp.components(separatedBy: "\r\n").map { line -> String in
            if line.hasPrefix("m=") { inVideo = line.hasPrefix("m=video") }
            guard inVideo, ["a=sendonly", "a=sendrecv", "a=recvonly"].contains(line) else { return line }
            turned = true
            return "a=inactive"
        }
        return turned ? lines.joined(separator: "\r\n") : nil
    }

    func setMuted(muted: Bool) {
        self.muted = muted
        audioTrack?.isEnabled = !muted
    }

    func collectStats(completion: @escaping (IosWebRtcStatsReport?) -> Void) {
        guard !closed else {
            completion(nil)
            return
        }
        connection.statistics { report in
            // Handed over as they are: picking the video stream and its candidate pair out of
            // the report is the shared module's job, the same as on Android.
            let stats = report.statistics.values.map { IosWebRtcStat(id: $0.id, type: $0.type, values: $0.values) }
            let converted = IosWebRtcStatsReport(timestampMs: report.timestamp_us / 1000, stats: stats)
            DispatchQueue.main.async { completion(converted) }
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        offerCompletion = nil
        if let track = videoTrack {
            track.remove(frameCounter)
            for entry in renderers.values { track.remove(entry.view) }
        }
        for entry in renderers.values { entry.view.removeFromSuperview() }
        renderers.removeAll()
        videoTrack = nil
        audioTrack = nil
        connection.close()
    }

    // MARK: RTCPeerConnectionDelegate

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        guard newState == .complete else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, let completion = self.offerCompletion else { return }
            self.offerCompletion = nil
            completion(self.connection.localDescription?.sdp, nil)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.closed else { return }
            switch newState {
            case .connected: self.listener.onConnected()
            case .disconnected: self.listener.onDisconnected()
            case .failed: self.listener.onFailed(reason: "peer connection failed")
            case .closed: self.listener.onClosed()
            case .new, .connecting: break
            @unknown default: break
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        guard let track = rtpReceiver.track else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.closed else { return }
            if let video = track as? RTCVideoTrack {
                NSLog("HomeSafeLive: peer %@ video track %@", self.logId, self.videoTrack == nil ? "added" : "added again")
                self.videoTrack = video
                // Always counting, so a peer nobody is drawing still reports its first frame.
                video.add(self.frameCounter)
                if self.videoEnabled {
                    for entry in self.renderers.values { video.add(entry.view) }
                }
            } else if let audio = track as? RTCAudioTrack {
                self.audioTrack = audio
                audio.isEnabled = !self.muted
                self.listener.onAudioTrack()
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

/// Counts decoded frames on the track itself, so the peer knows its video is flowing whether or not anyone is drawing it.
private final class FrameCounter: NSObject, RTCVideoRenderer {
    var onFirstFrame: (() -> Void)?
    private var seenFrame = false
    private let lock = NSLock()
    private var nextFrameMessage: String?

    /// Logs `message` when the next frame is decoded: the moment a resumed stream is moving again.
    func reportNextFrame(as message: String) {
        lock.lock()
        nextFrameMessage = message
        lock.unlock()
    }

    /// True from `reportNextFrame` until that frame comes.
    var isAwaitingFrame: Bool {
        lock.lock()
        defer { lock.unlock() }
        return nextFrameMessage != nil
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard frame != nil else { return }
        lock.lock()
        let message = nextFrameMessage
        nextFrameMessage = nil
        lock.unlock()
        if let message { NSLog("HomeSafeLive: %@", message) }
        guard !seenFrame else { return }
        seenFrame = true
        onFirstFrame?()
    }
}

/// A Metal view reports its first frame as a size change from zero; that is the moment this container has a picture.
private final class FirstFrameDelegate: NSObject, RTCVideoViewDelegate {
    var onFirstFrame: (() -> Void)?
    private var fired = false

    init(onFirstFrame: @escaping () -> Void) {
        self.onFirstFrame = onFirstFrame
    }

    func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        guard size.width > 0, size.height > 0, !fired else { return }
        fired = true
        let callback = onFirstFrame
        DispatchQueue.main.async { callback?() }
    }
}
