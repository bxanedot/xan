import SwiftUI
import AVFoundation
import UIKit
import UniformTypeIdentifiers
import WebKit
import XanShared

@main
struct XanIOSApp: App {
    @Environment(\.scenePhase) private var scenePhase
    private let roomStore = KeychainRoomSessionStore()

    var body: some Scene {
        WindowGroup {
            XanRootView(roomStore: roomStore)
                .onChange(of: scenePhase) { phase in
                    if phase == .active {
                        IosListenTogether.shared.onForeground()
                    } else if phase == .background {
                        IosListenTogether.shared.onBackground()
                    }
                }
        }
    }
}

private struct XanRootView: View {
    let roomStore: KeychainRoomSessionStore
    @StateObject private var audio = IosAudioPlayer.shared
    @State private var showingImporter = false
    @State private var streamURL = ""
    @State private var showingYouTubeLogin = false
    @State private var youTubeLoginCallback: IosYouTubeLoginCallback?
    @State private var youTubeLoginCompleted = false

    var body: some View {
        MainViewControllerRepresentable(roomStore: roomStore, onYouTubeLoginRequested: { callback in
            DispatchQueue.main.async {
                youTubeLoginCallback = callback
                youTubeLoginCompleted = false
                showingYouTubeLogin = true
            }
        })
            .safeAreaInset(edge: .bottom, spacing: 0) {
                audioDock
            }
            .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): audio.importFiles(urls)
                case .failure(let error): audio.setError(error.localizedDescription)
                }
            }
            .sheet(isPresented: $showingYouTubeLogin, onDismiss: {
                if !youTubeLoginCompleted {
                    youTubeLoginCallback?.onLoginCancelled()
                }
                youTubeLoginCallback = nil
                youTubeLoginCompleted = false
            }) {
                YouTubeMusicLoginSheet(
                    onCookie: { cookie, dataSyncId in
                        youTubeLoginCompleted = true
                        youTubeLoginCallback?.onLoginCookie(cookie: cookie, dataSyncId: dataSyncId)
                        showingYouTubeLogin = false
                    },
                    onError: { message in
                        youTubeLoginCallback?.onLoginError(message: message)
                    },
                )
            }
    }

    private var audioDock: some View {
        VStack(spacing: 8) {
            if !audio.title.isEmpty {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(audio.title).font(.subheadline.weight(.semibold)).lineLimit(1).foregroundStyle(.white)
                        Text(audio.artist).font(.caption).lineLimit(1).foregroundStyle(.white.opacity(0.68))
                    }
                    Spacer(minLength: 4)
                    Button { audio.previous() } label: { Image(systemName: "backward.end.fill") }
                        .accessibilityLabel("Previous track")
                    Button { audio.togglePlayback() } label: { Image(systemName: audio.isPlaying ? "pause.fill" : "play.fill") }
                        .accessibilityLabel(audio.isPlaying ? "Pause" : "Play")
                    Button { audio.next() } label: { Image(systemName: "forward.end.fill") }
                        .accessibilityLabel("Next track")
                    Button { showingImporter = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("Add audio files")
                }
                HStack(spacing: 18) {
                    Button { IosMusicPlayback.shared.toggleShuffle() } label: {
                        Label(audio.shuffleEnabled ? "Shuffle on" : "Shuffle off", systemImage: "shuffle")
                            .foregroundStyle(audio.shuffleEnabled ? Color(red: 1, green: 0.83, blue: 0) : .white.opacity(0.65))
                    }
                    .accessibilityLabel(audio.shuffleEnabled ? "Disable shuffle" : "Enable shuffle")
                    .disabled(!audio.canChangePlaybackModes)
                    Button { IosMusicPlayback.shared.cycleRepeatMode() } label: {
                        Label("Repeat \(audio.repeatMode.lowercased())", systemImage: audio.repeatMode == "ONE" ? "repeat.1" : "repeat")
                            .foregroundStyle(audio.repeatMode == "OFF" ? .white.opacity(0.65) : Color(red: 1, green: 0.83, blue: 0))
                    }
                    .accessibilityLabel("Repeat \(audio.repeatMode.lowercased())")
                    .disabled(!audio.canChangePlaybackModes)
                    Spacer()
                }
                .font(.caption.weight(.medium))
                if audio.durationSeconds > 0 {
                    Slider(value: Binding(get: { audio.positionSeconds }, set: { audio.seek(to: $0) }), in: 0...audio.durationSeconds)
                        .tint(Color(red: 1, green: 0.83, blue: 0))
                        .accessibilityLabel("Playback position")
                }
            } else {
                HStack {
                    Text("Play local audio or a secure stream").font(.footnote.weight(.medium)).foregroundStyle(.white.opacity(0.8))
                    Spacer()
                    Button("Choose files") { showingImporter = true }
                        .font(.footnote.weight(.semibold))
                        .tint(Color(red: 1, green: 0.83, blue: 0))
                }
            }
            HStack(spacing: 8) {
                TextField("https:// audio stream URL", text: $streamURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .font(.footnote)
                    .padding(.horizontal, 10)
                    .frame(height: 34)
                    .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 9))
                    .foregroundStyle(.white)
                Button("Play URL") { audio.playSecureURL(streamURL) }
                    .font(.footnote.weight(.semibold))
                    .tint(Color(red: 1, green: 0.83, blue: 0))
            }
            if let error = audio.errorMessage {
                Text(error).font(.caption).foregroundStyle(Color(red: 1, green: 0.55, blue: 0.5)).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .frame(maxWidth: 900)
        .frame(maxWidth: .infinity)
        .background(Color(red: 0.10, green: 0.10, blue: 0.10).opacity(0.98))
        .overlay(alignment: .top) { Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1) }
    }
}

private struct MainViewControllerRepresentable: UIViewControllerRepresentable {
    let roomStore: KeychainRoomSessionStore
    let onYouTubeLoginRequested: (IosYouTubeLoginCallback) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        IosAudioSession.shared.configure()
        IosMusicPlayback.shared.installNativePlayer(player: IosAudioBridge.shared.player)
        IosMusicPlayback.shared.installNativeExtractor(extractor: IosAudioBridge.shared.extractor)
        IosMusicPlayback.shared.installNativeDownloader(downloader: IosAudioBridge.shared.downloader)
        IosYouTubeLogin.shared.install(presenter: IosYouTubeLoginPresenterBridge(onPresent: onYouTubeLoginRequested))
        return MainViewControllerKt.MainViewController(sessionStore: roomStore)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

private final class IosYouTubeLoginPresenterBridge: NSObject, IosYouTubeLoginPresenter {
    private let onPresent: (IosYouTubeLoginCallback) -> Void

    init(onPresent: @escaping (IosYouTubeLoginCallback) -> Void) {
        self.onPresent = onPresent
    }

    func presentLogin(callback: IosYouTubeLoginCallback) {
        DispatchQueue.main.async { [onPresent] in onPresent(callback) }
    }
}

private struct YouTubeMusicLoginSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var statusMessage = "Sign in with Google in this page. XAN saves your YouTube session when sign-in completes."
    let onCookie: (String, String?) -> Void
    let onError: (String) -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(statusMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                YouTubeMusicLoginWebView(
                    onCookie: onCookie,
                    onStatus: { statusMessage = $0 },
                    onError: { message in
                        statusMessage = message
                        onError(message)
                    },
                )
            }
            .navigationTitle("YouTube Music sign-in")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }
}

private struct YouTubeMusicLoginWebView: UIViewRepresentable {
    let onCookie: (String, String?) -> Void
    let onStatus: (String) -> Void
    let onError: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onCookie: onCookie, onStatus: onStatus, onError: onError)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: URL(string: "https://music.youtube.com")!))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let onCookie: (String, String?) -> Void
        private let onStatus: (String) -> Void
        private let onError: (String) -> Void
        private var completed = false

        init(onCookie: @escaping (String, String?) -> Void, onStatus: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
            self.onCookie = onCookie
            self.onStatus = onStatus
            self.onError = onError
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard navigationAction.request.url?.scheme?.lowercased() == "https" else {
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !completed,
                  let host = webView.url?.host?.lowercased(),
                  host == "youtube.com" || host.hasSuffix(".youtube.com") else { return }

            webView.evaluateJavaScript("window.yt && window.yt.config_ && window.yt.config_.DATASYNC_ID") { [weak self, weak webView] value, _ in
                guard let self, let webView else { return }
                webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                    let youtubeCookies = cookies
                        .filter { cookie in
                            let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
                            return domain == "youtube.com" || domain.hasSuffix(".youtube.com")
                        }
                        .sorted { $0.domain.count < $1.domain.count }
                    var valuesByName: [String: String] = [:]
                    for cookie in youtubeCookies where !cookie.value.isEmpty {
                        valuesByName[cookie.name] = cookie.value
                    }
                    guard valuesByName["SAPISID"]?.isEmpty == false else {
                        DispatchQueue.main.async {
                            self.onStatus("Sign in with Google, then wait for YouTube Music to finish loading.")
                        }
                        return
                    }
                    let cookieHeader = valuesByName.keys.sorted().map { "\($0)=\(valuesByName[$0]!)" }.joined(separator: "; ")
                    guard cookieHeader.utf8.count <= 16_384,
                          cookieHeader.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value <= 0x7E }) else {
                        DispatchQueue.main.async {
                            let message = "The YouTube Music session could not be read safely. Finish signing in and try again."
                            self.onStatus(message)
                            self.onError(message)
                        }
                        return
                    }
                    DispatchQueue.main.async {
                        guard !self.completed else { return }
                        self.completed = true
                        self.onStatus("Sign-in complete. Saving your YouTube Music session to Keychain…")
                        self.onCookie(cookieHeader, value as? String)
                    }
                }
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            let message = "Could not load YouTube Music sign-in: \(error.localizedDescription)"
            DispatchQueue.main.async { self.onError(message) }
        }
    }
}

private final class IosAudioBridge {
    static let shared = IosAudioBridge()
    let player = IosNativeAudioPlayerBridge()
    let extractor = YouTubeAudioExtractor()
    let downloader = IosNativeAudioDownloaderBridge()
}

private final class IosNativeAudioPlayerBridge: NSObject, IosNativeAudioPlayer {
    func playStream(url: String, title: String, artist: String, artworkUrl: String?, startPositionMillis: Int64, autoplay: Bool) {
        Task { @MainActor in
            IosAudioPlayer.shared.playResolvedURL(url, title: title, artist: artist, artworkURL: artworkUrl, startPositionMillis: startPositionMillis, autoplay: autoplay)
        }
    }

    func resume() { Task { @MainActor in IosAudioPlayer.shared.applyRoomPlay() } }

    func pause() { Task { @MainActor in IosAudioPlayer.shared.applyRoomPause() } }

    func seekTo(positionMillis: Int64) {
        Task { @MainActor in IosAudioPlayer.shared.applyRoomSeek(positionMillis: positionMillis) }
    }

    func playLocalFile(filePath: String, title: String, artist: String, artworkUrl: String?) {
        Task { @MainActor in
            IosAudioPlayer.shared.playDownloadedFile(path: filePath, title: title, artist: artist, artworkURL: artworkUrl)
        }
    }

    func replayCurrent() { Task { @MainActor in IosAudioPlayer.shared.replayCurrent() } }

    func schedulePauseAfter(milliseconds: Int64, generation: Int64) {
        Task { @MainActor in IosAudioPlayer.shared.schedulePauseAfter(milliseconds: milliseconds, generation: generation) }
    }

    func cancelPauseTimer() {
        Task { @MainActor in IosAudioPlayer.shared.cancelPauseTimer() }
    }

    func updatePlaybackModes(shuffleEnabled: Bool, repeatMode: String, canChangeModes: Bool) {
        Task { @MainActor in
            IosAudioPlayer.shared.updatePlaybackModes(shuffleEnabled: shuffleEnabled, repeatMode: repeatMode, canChangeModes: canChangeModes)
        }
    }
}

private final class IosNativeAudioDownloaderBridge: NSObject, IosNativeAudioDownloader {
    private let lock = NSLock()
    private var activeTask: URLSessionDownloadTask?

    func downloadAudio(url rawURL: String, fileName: String, callback: IosAudioDownloadCallback) {
        guard let url = URL(string: rawURL), IosDownloadedAudioStore.isTrustedStreamURL(url) else {
            deliverFailure(callback, "YouTube returned an invalid audio download URL.")
            return
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 180
        let task = URLSession.shared.downloadTask(with: request) { [weak self] temporaryURL, response, error in
            defer { self?.clearActiveTask() }
            if let error {
                self?.deliverFailure(callback, "Download failed: \(error.localizedDescription)")
                return
            }
            guard let temporaryURL,
                  let response = response as? HTTPURLResponse,
                  (200...299).contains(response.statusCode),
                  let finalURL = response.url,
                  IosDownloadedAudioStore.isTrustedStreamURL(finalURL) else {
                self?.deliverFailure(callback, "YouTube returned an invalid response for the audio download.")
                return
            }
            if response.expectedContentLength > IosDownloadedAudioStore.maximumDownloadBytes {
                self?.deliverFailure(callback, "This audio file is larger than the 250 MB offline download limit.")
                return
            }
            if let contentType = response.mimeType?.lowercased(),
               !["audio/mp4", "video/mp4", "application/octet-stream"].contains(contentType) {
                self?.deliverFailure(callback, "YouTube returned an unsupported audio format (\(contentType)).")
                return
            }

            do {
                let fileSize = try FileManager.default.attributesOfItem(atPath: temporaryURL.path)[.size] as? NSNumber
                guard let fileSize, fileSize.int64Value > 0,
                      fileSize.int64Value <= IosDownloadedAudioStore.maximumDownloadBytes else {
                    throw NSError(domain: "XAN.Download", code: 1, userInfo: [NSLocalizedDescriptionKey: "The downloaded audio file is empty or larger than 250 MB."])
                }
                let directory = try IosDownloadedAudioStore.downloadsDirectory(createIfNeeded: true)
                let destination = directory.appendingPathComponent(IosDownloadedAudioStore.safeFileName(fileName), isDirectory: false)
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.moveItem(at: temporaryURL, to: destination)
                IosDownloadedAudioStore.excludeFromBackup(destination)
                self?.deliverSuccess(callback, destination.path)
            } catch {
                self?.deliverFailure(callback, "Could not save the downloaded audio: \(error.localizedDescription)")
            }
        }
        lock.lock()
        activeTask = task
        lock.unlock()
        task.resume()
    }

    func cancel() {
        lock.lock()
        let task = activeTask
        activeTask = nil
        lock.unlock()
        task?.cancel()
    }

    func deleteDownloadedFile(filePath: String) -> Bool {
        guard let fileURL = IosDownloadedAudioStore.validatedLocalFileURL(filePath) else { return false }
        do {
            try FileManager.default.removeItem(at: fileURL)
            return true
        } catch {
            return false
        }
    }

    private func clearActiveTask() {
        lock.lock()
        activeTask = nil
        lock.unlock()
    }

    private func deliverSuccess(_ callback: IosAudioDownloadCallback, _ path: String) {
        DispatchQueue.main.async { callback.onSuccess(filePath: path) }
    }

    private func deliverFailure(_ callback: IosAudioDownloadCallback, _ message: String) {
        DispatchQueue.main.async { callback.onFailure(message: message) }
    }
}

enum IosDownloadedAudioStore {
    static let maximumDownloadBytes: Int64 = 250 * 1024 * 1024

    static func isTrustedStreamURL(_ url: URL) -> Bool {
        let host = url.host?.lowercased() ?? ""
        return url.scheme?.lowercased() == "https" &&
            url.user == nil && url.password == nil && url.fragment == nil &&
            (url.port == nil || url.port == 443) &&
            (host == "googlevideo.com" || host.hasSuffix(".googlevideo.com"))
    }

    static func downloadsDirectory(createIfNeeded: Bool) throws -> URL {
        try audioDirectory(named: "Downloads", createIfNeeded: createIfNeeded)
    }

    static func importedAudioDirectory(createIfNeeded: Bool) throws -> URL {
        try audioDirectory(named: "ImportedAudio", createIfNeeded: createIfNeeded)
    }

    private static func audioDirectory(named name: String, createIfNeeded: Bool) throws -> URL {
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw NSError(domain: "XAN.Download", code: 2, userInfo: [NSLocalizedDescriptionKey: "The app storage directory is unavailable."])
        }
        let directory = support.appendingPathComponent("XAN", isDirectory: true).appendingPathComponent(name, isDirectory: true).standardizedFileURL
        if createIfNeeded {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
            excludeFromBackup(directory)
        }
        return directory
    }

    static func copyImportedAudio(from source: URL) throws -> URL {
        let extensionName = source.pathExtension.lowercased()
        let supportedExtensions: Set<String> = ["m4a", "mp3", "aac", "wav", "aiff", "aif", "caf", "mp4", "flac", "alac"]
        guard supportedExtensions.contains(extensionName) else {
            throw NSError(domain: "XAN.Import", code: 1, userInfo: [NSLocalizedDescriptionKey: "This audio format is not supported for the local library."])
        }
        let values = try source.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true,
              let fileSize = values.fileSize,
              fileSize > 0,
              Int64(fileSize) <= maximumImportedAudioBytes else {
            throw NSError(domain: "XAN.Import", code: 2, userInfo: [NSLocalizedDescriptionKey: "The selected file is empty or larger than the 1 GB import limit."])
        }
        let directory = try importedAudioDirectory(createIfNeeded: true)
        let name = safeImportedFileName(source.deletingPathExtension().lastPathComponent)
        let destination = directory.appendingPathComponent("\(UUID().uuidString.lowercased())-\(name).\(extensionName)", isDirectory: false)
        try FileManager.default.copyItem(at: source, to: destination)
        excludeFromBackup(destination)
        return destination
    }

    static func validatedLocalFileURL(_ path: String) -> URL? {
        guard path.hasPrefix("/"), !path.contains("\\"), !path.contains("\0"), path.count <= 4096 else { return nil }
        let trustedDirectories = [
            try? downloadsDirectory(createIfNeeded: false).resolvingSymlinksInPath().standardizedFileURL,
            try? importedAudioDirectory(createIfNeeded: false).resolvingSymlinksInPath().standardizedFileURL,
        ].compactMap { $0 }
        let fileURL = URL(fileURLWithPath: path).resolvingSymlinksInPath().standardizedFileURL
        let supportedExtensions: Set<String> = ["m4a", "mp3", "aac", "wav", "aiff", "aif", "caf", "mp4", "flac", "alac"]
        guard trustedDirectories.contains(fileURL.deletingLastPathComponent()),
              supportedExtensions.contains(fileURL.pathExtension.lowercased()),
              let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey]),
              values.isRegularFile == true else { return nil }
        return fileURL
    }

    private static func safeImportedFileName(_ raw: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ._-")
        let cleaned = String(raw.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
            .trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        let bounded = String(cleaned.prefix(100))
        return bounded.isEmpty ? "XAN audio" : bounded
    }

    private static let maximumImportedAudioBytes: Int64 = 1_073_741_824

    static func safeFileName(_ raw: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ._-")
        let cleaned = String(raw.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
            .trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        let bounded = String(cleaned.prefix(120))
        let base = bounded.lowercased().hasSuffix(".m4a") ? bounded : "\(bounded.isEmpty ? "XAN audio" : bounded).m4a"
        return base
    }

    static func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try? mutableURL.setResourceValues(values)
    }
}
