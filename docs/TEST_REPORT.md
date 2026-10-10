# Test report

## 2026-10-11 repair and 1.0.0 release checks

- Desktop: 108 tests, zero failures; six optional live checks were skipped in the main suite. Native JavaFX playback, seek/fade recovery, queue behavior, and real Japanese pack install/remove/reinstall were exercised.
- Separate live playback check passed: YouTube audio advanced inside XAN and recovered through yt-dlp after an intentionally invalid stream.
- Android: 14 focused playback, Magnet, logo, and updater tests passed; signed release APKs were built for all four ABIs and as a universal APK. All five signatures and metadata were verified as version 1.0.0/code 1 against the newly generated certificate.
- Listen Together: 18 backend tests passed, including HTTP/WebSocket room behavior.
- Website: production compilation and TypeScript checks passed. The standalone website ZIP was built using hoisted dependencies to avoid requiring Windows symlink privileges; its server startup and homepage were checked locally.
- Release workflows passed actionlint. macOS signing is performed before upload and includes JVM/audio entitlements. Android certificate validation reads the public fingerprint file; private signing material stays outside source control.
- Windows packaging reserves MSI product version 2.0.0 for app version 1.0.0, preserving upgrade ordering after the earlier native 1.9.x scheme.
- Installing the reset Android build over the old signing identity was not attempted. Platform CI results are reported separately from these local results.

## Earlier Android fusion check

Run on 2026-10-10 from branch `feature/xan-unified-fusion`.

| Check | Result | Evidence / limit |
|---|---|---|
| `:app:testDebugUnitTest` | Pass | 229 Android unit tests completed successfully after fixing the `Screens` static initialization cycle and adding Magnet destination coverage. |
| `:app:assembleDebug` | Pass | ABI-specific APKs, including `mobile/android/app/build/outputs/apk/debug/app-universal-debug.apk`, were produced; these are not release artifacts. |
| `git diff --check` | Pass | No whitespace errors at implementation commit. |
| Solid-mode backdrop capture guard | Pass | All app `.layerBackdrop(...)` call sites now attach through a shared guard that skips recording when global glass is off, Solid UI is selected, or the device cannot render glass. Static source scan leaves only the underlying unguarded modifier definition and its guarded helper call. |
| Bottom overlay source review | Pass | Home's list reserves `MiniPlayerHeight` through `LocalPlayerAwareWindowInsets`; Search's composer and Magnet use system-bar/IME inset modifiers. Runtime reachability and keyboard behavior still need device validation. |
| Android source scan | Pass | No `jiosaavn`, `jio saavn`, or `saavn` match remains in Android source, resources, modules, or version catalog. |
| Debug runtime dependency scan | Pass | `:app:dependencies --configuration debugRuntimeClasspath` completed; no Saavn dependency matched. |
| APK content scan | Pass | Decompressed entries in `app-universal-debug.apk` were scanned for `jiosaavn`, `jio saavn`, and `saavn`; no matches. This is a debug build, not a release artifact. |
| Android instrumentation / screenshot / accessibility tests | Not run | Canonical references are now preserved in `docs/design/reference/`, but `adb` and an emulator/device were unavailable for capturing the built app or running TalkBack checks. |
| Existing-install upgrade / playback / download journey | Not run | No installed XAN baseline or device session was modified. |
| Release APK/signing/update install | Not run | No release artifact is claimed. |
| iOS, desktop, web, backend builds | Not run | Out of scope for this Android implementation slice. |
| Comparison repositories | Not run | They were inspected as source snapshots and were not adopted as build inputs. |

The first unit-test attempt failed because the eagerly initialized `Screens.MainRoutes` accessed nested destination objects while the outer class was still initializing. The route lists were changed to lazy values; the rerun passed. The current screenshot follow-up changes were also built with `:app:testDebugUnitTest :app:assembleDebug`; both tasks completed successfully, with the test task reporting 229 tests.
