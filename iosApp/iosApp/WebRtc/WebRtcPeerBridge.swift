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
final class WebRtcPeerBridgeFactory: NSObject, IosWebRtcPeerFactory {

    static let shared = WebRtcPeerBridgeFactory()

    private lazy var factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        // Playback only. libwebrtc's default session is play-and-record for voice calls, which
        // routes to the earpiece and would ask for the microphone; nothing here ever records.
        let audio = RTCAudioSessionConfiguration.webRTC()
        audio.category = AVAudioSession.Category.playback.rawValue
        audio.mode = AVAudioSession.Mode.moviePlayback.rawValue
        audio.categoryOptions = []
        RTCAudioSessionConfiguration.setWebRTC(audio)
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
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
        connection.addTransceiver(of: .video, init: recvOnly)
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
        connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { error in
            DispatchQueue.main.async { completion(error?.localizedDescription) }
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

    func setMuted(muted: Bool) {
        self.muted = muted
        audioTrack?.isEnabled = !muted
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

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard frame != nil, !seenFrame else { return }
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
