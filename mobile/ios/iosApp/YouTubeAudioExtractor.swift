import Foundation
import YouTubeKit
import XanShared

final class YouTubeAudioExtractor: NSObject, IosNativeStreamExtractor {
    private var extractionTask: Task<Void, Never>?

    func resolveAudio(videoId: String, callback: IosStreamExtractionCallback) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.extractionTask?.cancel()
            self.extractionTask = Task {
                do {
                    let streams = try await YouTube(videoID: videoId, methods: [.local]).streams
                    try Task.checkCancellation()
                    let audio = streams.filter {
                        $0.includesAudioTrack && !$0.includesVideoTrack &&
                        $0.fileExtension == .m4a && $0.isNativelyPlayable
                    }.max {
                        ($0.bitrate ?? $0.averageBitrate ?? 0) < ($1.bitrate ?? $1.averageBitrate ?? 0)
                    }
                    guard let stream = audio else {
                        throw NSError(domain: "XAN.YouTube", code: 1, userInfo: [NSLocalizedDescriptionKey: "YouTube returned no playable AAC audio stream."])
                    }
                    let host = stream.url.host?.lowercased() ?? ""
                    guard stream.url.scheme == "https",
                          stream.url.user == nil, stream.url.password == nil,
                          stream.url.fragment == nil,
                          stream.url.port == nil || stream.url.port == 443,
                          host == "googlevideo.com" || host.hasSuffix(".googlevideo.com") else {
                        throw NSError(domain: "XAN.YouTube", code: 2, userInfo: [NSLocalizedDescriptionKey: "YouTube returned an invalid audio endpoint."])
                    }
                    try Task.checkCancellation()
                    await MainActor.run { callback.onResolved(url: stream.url.absoluteString) }
                } catch is CancellationError {
                    // Kotlin has cancelled or replaced this playback request.
                } catch {
                    await MainActor.run { callback.onFailure(message: "YouTube audio extraction failed: \(error.localizedDescription)") }
                }
            }
        }
    }

    func cancel() {
        DispatchQueue.main.async { [weak self] in
            self?.extractionTask?.cancel()
            self?.extractionTask = nil
        }
    }
}
