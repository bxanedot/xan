# Repository guide

The repository groups mobile platforms under `mobile/` and desktop platforms under `desktop/`. Android is the reference application. `desktop/shared/` contains the JVM Compose desktop app; `mobile/ios/` contains the Compose Multiplatform layer and Swift host. Both ports remain incomplete; their feature audits distinguish implementation from verified runtime behavior.

| Path | Purpose |
| --- | --- |
| `mobile/android/` | Complete Android Gradle project: app, feature modules, branding, wrapper, and version. |
| `mobile/ios/` | iPhone/iPad shared Kotlin code, Swift native host, XcodeGen specification, and branding. |
| `desktop/shared/` | Desktop application, services, independent Gradle build, packaging scripts, and parity audit. |
| `desktop/windows/` | Platform packaging assets and build notes; app source is shared in `desktop/shared/`. |
| `desktop/linux/` | Platform packaging assets and build notes; app source is shared in `desktop/shared/`. |
| `desktop/macos/` | Platform packaging assets and build notes; app source is shared in `desktop/shared/`. |
| `web/` | Next.js website and its Vercel project root. See [website guide](../web/README.md). |
| `backend/listen-together/` | Node.js WebSocket synchronization service. |
| `docs/` | Shared documentation, README screenshots, and [licensing notes](LICENSING.md). |
| `tools/` | Repository maintenance, Android diagnostics, and asset scripts. |
| `.github/` | CI and release workflows shared by the repository. |

## Android project

Keep Android source and its build system together under `mobile/android/`. Its Gradle settings include the app and Kotlin feature modules, so open `mobile/android/` in Android Studio and run the Gradle wrapper from that directory.

```powershell
cd mobile/android
.\gradlew.bat :app:assembleDebug
```

```bash
cd mobile/android
./gradlew :app:assembleDebug
```

The debug APK is written to `mobile/android/app/build/outputs/apk/debug/`. Release builds produce one universal APK containing the supported Android ABIs.

## Other projects

For the website, run `npm ci`, `npm run typecheck`, and `npm run build` from `web/` with Node.js 24 or newer. Vercel should continue to use `web/` as its project root.

The Listen Together server can be built and deployed from `backend/listen-together/` independently from Android and the website.

## Platform source and branding

Each platform keeps package-ready copies of XAN brand assets beside its source. Android is the canonical logo catalog: desktop and iOS build tasks sync its marks into their own resource formats before packaging, and the iOS app-icon generator reads Android's Gold mark. The platform copies let each native project package the approved identity in its own format.

The desktop build compiles selected platform-neutral Android provider sources and copies the central room protocol as generated source. The iOS layer shares that protocol and uses Swift for AVPlayer, Keychain, and local YouTubeKit extraction. Android-only UI/media classes are not compiled into desktop or iOS. See [desktop](../desktop/shared/README.md) and [iOS](../mobile/ios/IMPLEMENTATION.md) for commands and verification limits.

## Keeping desktop and iOS aligned with Android

Android is the feature reference. `mobile/android/version.properties`, the Listen Together protocol, and Android AppLogo assets feed the other builds directly. Desktop and iOS versions, room protocol sources, and logo resources are generated or synchronized at build time.

Every change under `mobile/android/` starts Desktop CI, iOS CI, and the **Android Platform Parity** workflow on pull requests and on pushes to `main`. The parity workflow summarizes changed Android areas, checks that Android/desktop/iOS expose the same logo choices, and syncs Android logos into platform resources. It calls out Android behavior or provider changes that have no matching platform-directory changes so a reviewer can confirm or port them. Feature-specific Kotlin/Compose behavior still needs a deliberate desktop/iOS implementation; CI cannot safely translate arbitrary Android UI or platform APIs.

## Adding another platform

Keep each target in its platform family: phone and tablet targets under `mobile/`, desktop targets under `desktop/`. The current layout already provides `mobile/android/`, `mobile/ios/`, and `desktop/windows/`, `desktop/linux/`, and `desktop/macos/`; desktop app code that can run across those three hosts belongs in `desktop/shared/`.

When adding a target:

1. Add its platform-specific project, packaging files, and a short README alongside its family peers.
2. Keep host APIs, signing, installers, and platform packaging in that target folder. Put code in a shared module only when it builds and behaves the same across its consumers.
3. Add the target and its current verification status to the root platform table and this layout guide.
4. Add or extend CI for its native build, and update the platform parity audit when it consumes Android behavior, shared protocol, or brand assets.
5. Record feature support and verification limits in the target README; a configured build is not evidence of runtime verification on that operating system.

For example, a future mobile target such as another phone OS gets its own `mobile/<platform>/` directory; an additional desktop host gets `desktop/<platform>/` and can reuse `desktop/shared/` where the implementation is genuinely host-neutral. Keep the existing Android, iOS, Windows, Linux, and macOS project paths stable so their build scripts and CI continue to work.
