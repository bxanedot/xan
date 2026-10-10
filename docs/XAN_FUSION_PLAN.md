# XAN Fusion implementation status

Prepared 2026-10-10 on `feature/xan-unified-fusion` from the current default branch. This document records a focused implementation pass, not completion of the full multi-platform fusion program.

## Repository audit

All four latest default-branch snapshots were checked out separately under the task workspace. XAN is the only destination repository.

| Repository | Branch / commit | Commit date | License | Build status in this pass |
|---|---|---|---|---|
| [XAN](https://github.com/bxanedot/xan) | `main` / `80cecf5b6afed1eff1c2d9947d7170d67c018dc6` | 2026-10-10 | GPL-3.0-only | Android debug build and unit tests pass after changes. |
| [ViviMusic](https://github.com/vivizzz007/vivi-music) | `main` / `1dcd5d9b9af9e47eadbadba6224a7f8237f12a39` | 2026-10-01 | GPL-3.0 with a Musixmatch exception | Not built; source comparison only. |
| [Meld](https://github.com/FrancescoGrazioso/Meld) | `main` / `0899f43e94aa91495fa1d1865f80dc6256de9035` | 2026-10-10 | GPL-3.0 | Not built; source comparison only. |
| [Convx](https://github.com/cosmictaserdev-creator/Convx) | `main` / `1e2237d9f8dd56de1c8a97dffc9c31e6596c437a` | 2026-08-20 | GPL-3.0 | Not built; source comparison only. |

The comparison checkouts are shallow snapshots, so full commit ancestry was not established. ViviMusic and Convx have overlapping module names and both include a JioSaavn module; no code from either was copied. ViviMusic's license explicitly bars copying its Musixmatch module into another application.

### Module map observed

- **XAN:** Android `app` plus `music-sources` (`innertube`, `kugou`, `spotify`), `lyrics` (`lrclib`, `betterlyrics`, `simpmusic`, `youlyplus`, `paxsenixlyrics`, `musixmatchlyrics`), `services` (`lastfm`, `shazamkit`, `kizzy`), and `visuals` (`canvas`, `artistvideo`, `applecanvas`, `xancanvas`). Separate iOS, desktop, web, and Listen Together backend trees remain in XAN.
- **ViviMusic:** `app`, `canvas`, `innertube`, `kizzy`, `lastfm`, `shazamkit`, `artistvideo`, `applecanvas`, `vivimusiccanvas`, `jiosaavn`, `spotify`, and `lyricsProvider`.
- **Meld:** Gradle `app` and `innertube`; a `notes` Git submodule is also declared. No JioSaavn module was found in this snapshot.
- **Convx:** `app`, `canvas`, `innertube`, `kugou`, `lrclib`, `kizzy`, `lastfm`, `betterlyrics`, `simpmusic`, `youlyplus`, `shazamkit`, `artistvideo`, `applecanvas`, `vivimusiccanvas`, `paxsenixlyrics`, `musixmatchlyrics`, `jiosaavn`, `spotify`, `spine`, and `listen-together-server`.

These names are inventory only. They do not establish module build health, legal compatibility, or a reason to import modules wholesale.

## Changes in this pass

- Magnet now exposes exactly Home, Search, Library, Downloads, and Settings. Its music-recognition quick action was removed; recognition remains available from existing search entry points. Shuffle is not part of the Magnet destination list.
- Downloads opens XAN's existing `auto_playlist/downloaded` destination. This reuses the current downloaded-items view and does not create a second manager.
- Appearance now presents Solid and Liquid Glass as explicit radio choices under `UI Style`. The existing persisted `SolidUiKey` remains the preference and its fresh-install fallback remains Solid.
- The only remaining Saavn-named field in the XAN checkout was unused `PlaybackData.isSaavnStream`; it was removed. No JioSaavn module, dependency, UI route, or provider code existed in XAN's current tree.
- The Gradle wrapper was updated from 9.8.0 to 9.8.1 with the checksum published by Gradle.
- Route catalogs are now initialized lazily. The new unit test exposed an eager static-initialization cycle when `Screens` was first accessed in a local unit test.

## Scope still open

- The three canonical screenshots are now preserved under [`docs/design/reference/`](design/reference/README.md). Their layouts were visually reviewed; Search selected-tab contrast was corrected in Compose, and backdrop recording is now gated across app screens and glass controls so Solid mode skips capture work. A pixel-diff comparison or image-based golden test remains unavailable because this environment has no emulator/device or `adb`.
- The 38 links have an accountability matrix, but this pass did not redesign the website, capture real device screenshots, import third-party UI code, or audit every page's license terms.
- No complete Spotify authorization/recommendation pipeline, provider matching framework, lyrics rewrite, migration suite, release build/signing validation, SBOM, or full security/dependency scan was performed.
- iOS, desktop, web, and Listen Together behavior were not changed or claimed at parity. Existing repository documentation describes those platform ports as incomplete.
- Comparison repositories were not built. Their build status is therefore unknown, not green.

See [FEATURE_MATRIX.md](FEATURE_MATRIX.md), [DEPENDENCY_AUDIT.md](DEPENDENCY_AUDIT.md), and [design/RESOURCE_ADOPTION_MATRIX.md](design/RESOURCE_ADOPTION_MATRIX.md) for evidence and remaining work.
