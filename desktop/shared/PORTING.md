# XAN Desktop: remaining port work

XAN Desktop already has an independent Compose Desktop application, a persistent SQLite catalogue, online search and playback, local library scanning, downloads, editable playlists, account integrations, room controls, native Windows packaging, and a feature-by-feature audit. The current implementation and evidence live in [FEATURE_PARITY.md](FEATURE_PARITY.md) and [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md); treat those files as the status source.

## Highest-priority remaining work

1. Add native operating-system media sessions and per-device audio routing; verify the opt-in global media keys and background playback on Windows, Linux, and macOS.
2. Exercise Listen Together with a reachable shared server and Android clients, including room permissions, queue behavior, drift correction, disconnects, and reconnection.
3. Build and launch native packages on macOS and Linux, then verify installer/uninstaller behavior, high-DPI scaling, file access, credential storage, notifications, and audio backends on each platform.
4. Configure the Windows certificate and Apple signing/notarization secrets in the existing release workflow; keep Linux package checksums and update signatures verified.
5. Continue closing the Android parity gaps listed in `FEATURE_PARITY.md`, especially account/library screens, remaining settings, codecs, and feature-level UI/runtime review.

## Platform limits and checks

| Platform | Current artifact path | Required release check |
| --- | --- | --- |
| Windows x64 | MSI and app image under `desktop/shared/build/compose/binaries/main/` | Clean-machine install, signing trust, media keys, audio output, update handoff |
| Linux x64 | DEB/RPM and AppImage from CI/release workflow | Full GNOME/KDE desktop/audio integration, Wayland media-key limitations, XDG persistence, update handoff |
| macOS Intel and Apple Silicon | DMG from the macOS release workflow | Both architecture-specific DMG builds pass CI; verify permissions, accessibility media-key permission, audio output, signing and notarization |
| Windows ARM64 | Not currently packaged; JavaFX 21.0.10 media binaries do not include Windows ARM64 | Revisit when a supported JavaFX media runtime exists |

Build and exercise each native package on its target operating system. A successful Windows build does not verify Linux or macOS packages. The current test results, skipped live tests, and unsigned-platform limitations are recorded in `IMPLEMENTATION_STATUS.md`.
