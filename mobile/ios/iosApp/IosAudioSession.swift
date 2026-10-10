import SwiftUI
import AVFoundation
import MediaPlayer
import XanShared

final class IosAudioSession: NSObject {
    static let shared = IosAudioSession()
    private var interruptionObserver: NSObjectProtocol?
    private var routeObserver: NSObjectProtocol?
    private var nowPlayingTimer: Timer?
    private var configured = false
    private override init() { super.init() }

    func configure() {
        guard !configured else { return }
        configured = true
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.allowBluetoothA2DP, .allowAirPlay])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            NSLog("XAN audio session setup failed: %@", error.localizedDescription)
        }
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        center.previousTrackCommand.isEnabled = true
        center.changePlaybackPositionCommand.isEnabled = true
        center.playCommand.addTarget { _ in Task { @MainActor in IosAudioPlayer.shared.play() }; return .success }
        center.pauseCommand.addTarget { _ in Task { @MainActor in IosAudioPlayer.shared.pause() }; return .success }
        center.togglePlayPauseCommand.addTarget { _ in Task { @MainActor in IosAudioPlayer.shared.togglePlayback() }; return .success }
        center.nextTrackCommand.addTarget { _ in Task { @MainActor in IosAudioPlayer.shared.next() }; return .success }
        center.previousTrackCommand.addTarget { _ in Task { @MainActor in IosAudioPlayer.shared.previous() }; return .success }
        center.changePlaybackPositionCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in IosAudioPlayer.shared.seek(to: event.positionTime) }
            return .success
        }
        UIApplication.shared.beginReceivingRemoteControlEvents()
        interruptionObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance(), queue: .main) { notification in
            guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            Task { @MainActor in IosAudioPlayer.shared.pause() }
        }
        routeObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: AVAudioSession.sharedInstance(), queue: .main) { notification in
            guard let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            Task { @MainActor in IosAudioPlayer.shared.pause() }
        }
        nowPlayingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            Task { @MainActor in IosAudioPlayer.shared.refreshNowPlaying() }
        }
        Task { @MainActor in IosAudioPlayer.shared.refreshNowPlaying() }
    }
}
