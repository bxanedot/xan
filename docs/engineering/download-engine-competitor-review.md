# Download and offline architecture review

Scope: public engineering material and source code present in the local competitor snapshots on 2026-10-14. This review records only behavior visible in those sources or explicitly described by their publishers. It does not infer proprietary transfer internals from the appearance of a product feature.

## Findings

| Product | Verified architecture and download evidence | Applicable ideas for XAN | Limits |
| --- | --- | --- | --- |
| VIVI Music | The checked source snapshot is `bc94ca1`. Its Android app uses Media3 `DownloadManager`, `DownloadCache`, a persistent download index, and an `ExoDownloadService`; `DownloadUtil.kt` sets `maxParallelDownloads = 3` and updates the library from Media3 state callbacks. Its listener invalidates cached stream URLs on HTTP 403/410. | Keep Media3's durable index and cache; invalidate expired stream URLs before a user retry; expose terminal failures and preserve queue identity. | The inspected listener logs state and updates the library, but does not show an automatic post-failure queue coordinator, shared-storage publisher, or checksum verifier. The snapshot does not prove the absence of those features elsewhere or in newer releases. No code was copied. |
| Convx | The checked source snapshot is `1e2237d`. Its app also uses Media3 `DownloadManager` and `DownloadCache`, with the manager configured for three parallel downloads. `DownloadActionsTest.kt` verifies that failed/stopped requests may be queued again and completed requests must not be queued again. Its listener logs failed-download exceptions. | Retain a failed request's ID and metadata; distinguish failed/stopped from completed; keep duplicate handling covered by tests. | The inspected tests cover queue decisions, not network interruption, byte integrity, reboot recovery, or bulk throughput. The snapshot is closely related in structure to VIVI; it is not independent evidence for a different downloader design. No code was copied. |
| Spotify | Spotify's engineering posts describe client-side storage of liked-song metadata in batches, precomputed local sort orders, and storage on a device or SD card. Its Desktop/Web Player post describes TypeScript Platform APIs abstracting different data and playback stacks, and says downloading/offline mode was added to the shared UI. | Persist useful library metadata locally, avoid rebuilding expensive views on every launch, and place platform differences behind a stable API. | These posts do not disclose Spotify's current licensed-audio download protocol, retry policy, checksum scheme, or queue internals. The metadata batching post is about library metadata, not audio-file downloads. |
| Deezer | Deezer Support documents subscriber offline downloads for tracks, albums, and playlists, a Wi-Fi/mobile-data download setting, and an in-library view of downloaded content. | Make network policy explicit and place downloaded content in an easy-to-find library surface. | Public support material documents product behavior, not transfer internals. It does not establish concurrency, retry, resume, integrity, or file-encryption details. |

## XAN changes informed by the review

- Preserve Media3's indexed audio queue and its `DownloadCache`; use a WorkManager retry coordinator for bounded recoverable failures instead of treating a `STATE_FAILED` item as forgotten.
- Re-resolve stream URLs after an expired authorization response, while leaving permanent HTTP client errors visible for manual action.
- Keep the video job request in app-private storage and use unique WorkManager work with a connected-network constraint. Progressive video transfers resume only when URL and a strong ETag or Last-Modified validator match; invalid or changed ranges restart safely.
- Share the optional bandwidth limiter between audio and video, leave it unlimited by default, and adapt audio concurrency to measured throughput, server throttling, available memory, and disk space. Video jobs use the same adaptive slot budget.
- Publish completed media through pending MediaStore rows and validate before marking it complete. The storage option defaults to public `Music/XAN` and `Movies/XAN` so completed files survive app removal; turning the setting off does not delete existing public files.
- Show live transfer activity and let users retry failed audio requests from Storage settings.

## Evidence and source locations

Local source snapshots, pinned for this review:

- VIVI Music: `app/src/main/kotlin/com/music/vivi/playback/DownloadUtil.kt` and `app/src/main/kotlin/com/music/vivi/playback/ExoDownloadService.kt`, snapshot `bc94ca1`.
- Convx: `app/src/main/kotlin/com/convx/music/playback/DownloadUtil.kt`, `app/src/main/kotlin/com/convx/music/playback/DownloadActions.kt`, and `app/src/test/kotlin/com/convx/music/playback/DownloadActionsTest.kt`, snapshot `1e2237d`.
- XAN: `mobile/android/app/src/main/kotlin/app/xan/music/playback/DownloadUtil.kt`, `AudioDownloadRecoveryWorker.kt`, `PersistentAudioDownloadWorker.kt`, `video/ExternalVideoDownloadWorker.kt`, and `video/ResumableVideoTransfer.kt`.

Public product sources:

- Spotify Lite describes storage constraints, disk/SD-card handling, and a tailored playback stack: <https://engineering.atspotify.com/2020/12/how-we-built-it-spotify-lite-one-year-later>
- Spotify client-side metadata batching and on-disk sort indexes: <https://engineering.atspotify.com/2020/5/spotify-modernizes-client-side-architecture-to-accelerate-service-on-all-devices>
- Spotify Desktop/Web Player platform abstraction and offline feature set: <https://engineering.atspotify.com/2021/4/building-the-future-of-our-desktop-apps>
- Deezer's documented offline download and network settings: <https://support.deezer.com/hc/en-gb/articles/115003884605-Listen-To-Deezer-Offline>

## Verification boundary

These are source and documentation observations, not a reverse-engineering claim about closed-source services. Audio and video sources may reject a request, expire a signed URL, or restrict availability. A locally stored item is reported complete only after its transfer and publication checks succeed; no client can guarantee completion for an unavailable or permanently denied source.
