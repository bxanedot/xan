import AVFoundation
import Combine
import Foundation
import MediaPlayer
import UIKit
import UniformTypeIdentifiers
import XanShared

@MainActor
final class IosAudioPlayer: ObservableObject {
    static let shared = IosAudioPlayer()

    @Published private(set) var title = ""
    @Published private(set) var artist = ""
    @Published private(set) var isPlaying = false
    @Published private(set) var positionSeconds: Double = 0
    @Published private(set) var durationSeconds: Double = 0
    @Published private(set) var errorMessage: String?
    @Published private(set) var shuffleEnabled = false
    @Published private(set) var repeatMode = "OFF"
    @Published private(set) var canChangePlaybackModes = true

    private struct Entry {
        let url: URL
        let title: String
        let artist: String
        let artworkURL: String?
        let startPositionMillis: Int64
        let autoplay: Bool
        let securityScoped: Bool
        let libraryTrackId: String?
    }

    private var sourceEntries: [Entry] = []
    private var entries: [Entry] = []
    private var index = 0
    private var player: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var itemStatusObservation: NSKeyValueObservation?
    private var playerStatusObservation: NSKeyValueObservation?
    private var artworkTask: Task<Void, Never>?
    private var sleepTimerWorkItem: DispatchWorkItem?
    private var sleepTimerGeneration: Int64?
    private var currentArtwork: MPMediaItemArtwork?
    private var currentArtworkURL: String?
    private let artworkCache = NSCache<NSString, UIImage>()

    private init() {}

    func setError(_ message: String) { errorMessage = message }

    func updatePlaybackModes(shuffleEnabled: Bool, repeatMode: String, canChangeModes: Bool) {
        let shuffleChanged = self.shuffleEnabled != shuffleEnabled
        self.shuffleEnabled = shuffleEnabled
        self.repeatMode = ["OFF", "ALL", "ONE"].contains(repeatMode) ? repeatMode : "OFF"
        self.canChangePlaybackModes = canChangeModes
        if shuffleChanged { applyShuffleMode() }
    }

    func replayCurrent() {
        guard player != nil else { return }
        seekLocally(to: 0)
        player?.play()
        isPlaying = true
        refreshNowPlaying()
    }

    func schedulePauseAfter(milliseconds: Int64, generation: Int64) {
        cancelPauseTimer()
        guard milliseconds > 0 else { return }
        let workItem = DispatchWorkItem { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.sleepTimerGeneration == generation else { return }
                self.sleepTimerWorkItem = nil
                self.sleepTimerGeneration = nil
                IosMusicPlayback.shared.handleNativeSleepTimerExpired(generation: generation)
            }
        }
        sleepTimerWorkItem = workItem
        sleepTimerGeneration = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(milliseconds) / 1_000, execute: workItem)
    }

    func cancelPauseTimer() {
        sleepTimerWorkItem?.cancel()
        sleepTimerWorkItem = nil
        sleepTimerGeneration = nil
    }

    func importFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        IosMusicPlayback.shared.clearNativeTrack()
        releaseImportedFiles()
        var imported: [Entry] = []
        var failures: [String] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            var copiedURL: URL?
            do {
                let localURL = try IosDownloadedAudioStore.copyImportedAudio(from: url)
                copiedURL = localURL
                let asset = AVURLAsset(url: localURL)
                let rawTitle = asset.commonMetadata.first(where: { $0.identifier == .commonIdentifierTitle })?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
                let title = String((rawTitle.flatMap { $0.isEmpty ? nil : $0 } ?? localURL.deletingPathExtension().lastPathComponent).prefix(300))
                let rawArtist = asset.commonMetadata.first(where: { $0.identifier == .commonIdentifierArtist })?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
                let artist = String((rawArtist.flatMap { $0.isEmpty ? nil : $0 } ?? "Local audio").prefix(300))
                let album = asset.commonMetadata.first(where: { $0.identifier == .commonIdentifierAlbumName })?.stringValue
                    .map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300)) }
                    .flatMap { $0.isEmpty ? nil : $0 }
                let duration = asset.duration.seconds
                let trackId = "local-\(UUID().uuidString.lowercased())"
                IosMusicLibrary.shared.registerImportedAudio(
                    id: trackId,
                    title: title.isEmpty ? "Local audio" : title,
                    artist: artist.isEmpty ? "Local audio" : artist,
                    album: album,
                    durationSeconds: duration.isFinite && duration > 0 ? Int32(min(duration.rounded(), Double(Int32.max))) : 0,
                    localFilePath: localURL.path,
                )
                imported.append(Entry(url: localURL, title: title, artist: artist, artworkURL: nil, startPositionMillis: 0, autoplay: true, securityScoped: false, libraryTrackId: trackId))
            } catch {
                if let copiedURL { try? FileManager.default.removeItem(at: copiedURL) }
                failures.append("\(url.lastPathComponent): \(error.localizedDescription)")
            }
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        guard !imported.isEmpty else {
            errorMessage = failures.first ?? "XAN could not import the selected audio files."
            return
        }
        replaceEntries(imported)
        playCurrent()
        if !failures.isEmpty { errorMessage = "Imported \(imported.count) file(s); \(failures.count) could not be copied." }
    }

    func playSecureURL(_ raw: String) {
        IosMusicPlayback.shared.clearNativeTrack()
        playURL(raw, title: nil, artist: nil)
    }

    func playResolvedURL(_ raw: String, title: String, artist: String, artworkURL: String? = nil, startPositionMillis: Int64 = 0, autoplay: Bool = true) {
        playURL(raw, title: title, artist: artist, artworkURL: artworkURL, startPositionMillis: startPositionMillis, autoplay: autoplay)
    }

    func playDownloadedFile(path: String, title: String, artist: String, artworkURL: String?) {
        guard let url = IosDownloadedAudioStore.validatedLocalFileURL(path) else {
            errorMessage = "This local audio file is missing or outside XAN's library folders."
            return
        }
        releaseImportedFiles()
        replaceEntries([Entry(url: url, title: title, artist: artist, artworkURL: artworkURL, startPositionMillis: 0, autoplay: true, securityScoped: false, libraryTrackId: nil)])
        playCurrent()
    }

    private func playURL(_ raw: String, title: String?, artist: String?, artworkURL: String? = nil, startPositionMillis: Int64 = 0, autoplay: Bool = true) {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https", url.host != nil else {
            errorMessage = "Enter a secure https:// audio URL."
            return
        }
        releaseImportedFiles()
        let inferredTitle = url.deletingPathExtension().lastPathComponent.isEmpty ? url.host ?? "Audio stream" : url.deletingPathExtension().lastPathComponent
        let resolvedTitle = title.flatMap { $0.isEmpty ? nil : $0 } ?? inferredTitle
        let resolvedArtist = artist.flatMap { $0.isEmpty ? nil : $0 } ?? "Audio stream"
        replaceEntries([Entry(url: url, title: resolvedTitle, artist: resolvedArtist, artworkURL: artworkURL, startPositionMillis: max(0, startPositionMillis), autoplay: autoplay, securityScoped: false, libraryTrackId: nil)])
        playCurrent()
    }

    func togglePlayback() {
        if IosMusicPlayback.shared.routeNativeAction(action: isPlaying ? "pause" : "play") { return }
        togglePlaybackLocally()
    }

    private func togglePlaybackLocally() {
        guard let player else { return }
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
        refreshNowPlaying()
    }

    func play() {
        if IosMusicPlayback.shared.routeNativeAction(action: "play") { return }
        applyRoomPlay()
    }

    /// Applies a room update directly without sending the same command back to
    /// the room server.
    func applyRoomPlay() {
        guard player != nil else { return }
        player?.play()
        isPlaying = true
        refreshNowPlaying()
    }

    func pause() {
        if IosMusicPlayback.shared.routeNativeAction(action: "pause") { return }
        applyRoomPause()
    }

    /// Applies a room update directly without sending the same command back to
    /// the room server.
    func applyRoomPause() {
        guard player != nil else { return }
        player?.pause()
        isPlaying = false
        refreshNowPlaying()
    }

    func next() {
        if IosMusicPlayback.shared.routeNativeAction(action: "skip_next") { return }
        nextLocally()
    }

    private func nextLocally() {
        guard !entries.isEmpty else { return }
        if index + 1 < entries.count {
            index += 1
            playCurrent()
        } else if repeatMode == "ALL" {
            index = 0
            playCurrent()
        } else {
            applyRoomPause()
        }
    }

    func previous() {
        if IosMusicPlayback.shared.routeNativeAction(action: "skip_prev") { return }
        previousLocally()
    }

    private func previousLocally() {
        guard !entries.isEmpty else { return }
        if positionSeconds > 3 {
            seekLocally(to: 0)
        } else if index > 0 {
            index -= 1
            playCurrent()
        } else if repeatMode == "ALL" {
            index = entries.count - 1
            playCurrent()
        } else {
            seekLocally(to: 0)
        }
    }

    func seek(to seconds: Double) {
        guard seconds.isFinite, seconds >= 0 else { return }
        let bounded = durationSeconds > 0 ? min(seconds, durationSeconds) : seconds
        if IosMusicPlayback.shared.routeNativeSeek(positionMillis: Int64((bounded * 1_000).rounded())) { return }
        seekLocally(to: bounded)
    }

    /// Applies a room seek directly without sending the same seek back to the
    /// room server.
    func applyRoomSeek(positionMillis: Int64) {
        guard positionMillis >= 0 else { return }
        seekLocally(to: Double(positionMillis) / 1_000.0)
    }

    private func seekLocally(to seconds: Double) {
        guard seconds.isFinite, seconds >= 0, player != nil else { return }
        let bounded = durationSeconds > 0 ? min(seconds, durationSeconds) : seconds
        player?.seek(to: CMTime(seconds: bounded, preferredTimescale: 600))
        positionSeconds = bounded
        refreshNowPlaying()
    }

    func refreshNowPlaying() {
        guard !title.isEmpty else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        IosMusicPlayback.shared.updateNativeProgress(
            positionMillis: Int64(max(0, (positionSeconds * 1_000).rounded())),
            durationMillis: Int64(max(0, (durationSeconds * 1_000).rounded())),
            playing: isPlaying,
        )
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPMediaItemPropertyPlaybackDuration: durationSeconds,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: positionSeconds,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        if let currentArtwork { info[MPMediaItemPropertyArtwork] = currentArtwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func playCurrent() {
        guard entries.indices.contains(index) else { return }
        let entry = entries[index]
        artworkTask?.cancel()
        currentArtwork = nil
        currentArtworkURL = entry.artworkURL
        errorMessage = nil
        title = entry.title
        artist = entry.artist
        if let trackId = entry.libraryTrackId {
            IosMusicPlayback.shared.setImportedAudioMetadata(trackId: trackId, title: entry.title, artist: entry.artist)
        }
        positionSeconds = 0
        durationSeconds = 0
        if let timeObserver, let player { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        itemStatusObservation?.invalidate()
        let item = AVPlayerItem(url: entry.url)
        if player == nil { player = AVPlayer(playerItem: item) } else { player?.replaceCurrentItem(with: item) }
        guard let player else { return }
        player.automaticallyWaitsToMinimizeStalling = true
        if playerStatusObservation == nil {
            playerStatusObservation = player.observe(\AVPlayer.timeControlStatus, options: [.initial, .new]) { [weak self] observedPlayer, _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.isPlaying = observedPlayer.timeControlStatus == .playing
                    self.refreshNowPlaying()
                }
            }
        }
        itemStatusObservation = item.observe(\AVPlayerItem.status, options: [.initial, .new]) { [weak self] observedItem, _ in
            Task { @MainActor in
                guard let self, observedItem.status == .failed else { return }
                self.isPlaying = false
                self.errorMessage = observedItem.error?.localizedDescription ?? "This audio format or stream could not be played."
                self.refreshNowPlaying()
            }
        }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                self.positionSeconds = max(0, time.seconds.isFinite ? time.seconds : 0)
                let duration = player.currentItem?.duration.seconds ?? 0
                self.durationSeconds = duration.isFinite ? max(0, duration) : 0
                self.refreshNowPlaying()
            }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if IosMusicPlayback.shared.handleNativeTrackEnded() { return }
                if self.repeatMode == "ONE" || (self.repeatMode == "ALL" && self.entries.count == 1) {
                    self.replayCurrent()
                } else {
                    self.nextLocally()
                }
            }
        }
        if entry.startPositionMillis > 0 {
            player.seek(to: CMTime(value: entry.startPositionMillis, timescale: 1_000))
        }
        if entry.autoplay {
            player.play()
            isPlaying = true
        } else {
            player.pause()
            isPlaying = false
        }
        refreshNowPlaying()
        loadArtwork(for: entry.artworkURL)
    }

    private func loadArtwork(for rawURL: String?) {
        guard let rawURL,
              rawURL.count <= 4096,
              let url = URL(string: rawURL),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              host == "i.ytimg.com" || host == "googleusercontent.com" || host.hasSuffix(".googleusercontent.com") || host == "ggpht.com" || host.hasSuffix(".ggpht.com") else { return }
        if let cached = artworkCache.object(forKey: rawURL as NSString) {
            currentArtwork = MPMediaItemArtwork(boundsSize: cached.size) { _ in cached }
            refreshNowPlaying()
            return
        }
        artworkTask = Task { [weak self] in
            do {
                let (data, response) = try await URLSession.shared.data(from: url)
                guard let response = response as? HTTPURLResponse,
                      (200...299).contains(response.statusCode),
                      data.count <= 8 * 1024 * 1024,
                      let image = UIImage(data: data),
                      !Task.isCancelled else { return }
                guard let self, self.currentArtworkURL == rawURL else { return }
                self.artworkCache.setObject(image, forKey: rawURL as NSString)
                self.currentArtwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                self.refreshNowPlaying()
            } catch {
                // Artwork is optional; audio playback remains available if the image request fails.
            }
        }
    }

    private func releaseImportedFiles() {
        for entry in entries where entry.securityScoped {
            entry.url.stopAccessingSecurityScopedResource()
        }
        entries.removeAll()
        sourceEntries.removeAll()
    }

    private func replaceEntries(_ replacement: [Entry]) {
        sourceEntries = replacement
        entries = replacement
        index = 0
        applyShuffleMode()
    }

    private func applyShuffleMode() {
        guard entries.count > 1 else { return }
        if shuffleEnabled {
            let visitedAndCurrent = Array(entries.prefix(index + 1))
            entries = visitedAndCurrent + Array(entries.dropFirst(index + 1)).shuffled()
            index = visitedAndCurrent.count - 1
        } else if let current = entries.indices.contains(index) ? entries[index] : nil,
                  let restoredIndex = sourceEntries.firstIndex(where: { $0.url == current.url }) {
            entries = sourceEntries
            index = restoredIndex
        }
    }
}
