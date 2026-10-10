# Android dependency audit

Audited 2026-10-10 from `mobile/android/gradle/libs.versions.toml`, the wrapper, Android build configuration, and upstream release/compatibility pages. Only the wrapper patch changed in this implementation pass.

| Artifact | Checked-in version after this pass | Upstream check | Compatibility / result | Change |
|---|---:|---|---|---|
| Kotlin Gradle plugin | 2.4.21 | [Kotlin release history](https://kotlinlang.org/docs/releases.html) lists 2.4.21 as released 2026-10-08 | Android debug build passes. The published KGP compatibility table currently documents 2.4.20 through Gradle 9.7.0 / AGP 9.3.1; this project uses newer Gradle/AGP, so the pairing is beyond that published fully-supported range. | None |
| Android Gradle Plugin | 9.4.1 | [Android AGP API](https://developer.android.com/reference/tools/gradle-api) lists 9.4.1 as current; [compatibility notes](https://developer.android.com/build/releases/about-agp) require Gradle 9.6.0 minimum | Gradle 9.8.1 exceeds AGP's minimum. Debug build passes. | None |
| Gradle wrapper | 9.8.1 | [Gradle 9.8.1 release notes](https://docs.gradle.org/9.8.1/release-notes.html) recommend the patch; [official checksums](https://gradle.org/release-checksums/) supplied the pinned binary ZIP checksum | Wrapper downloaded with checksum verification; Android debug build and unit tests pass. | Updated from 9.8.0 |
| AndroidX Media3 | 1.11.1 | [AndroidX Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3) list 1.11.1 as the stable release | Project compiles against the existing version. Playback was not exercised. | None |
| NewPipeExtractor | 0.26.5 | [Upstream releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases) list v0.26.5 | Existing dependency resolves in the Android build. Extraction/playback behavior was not tested. | None |
| InnerTubeX | v0.8.4 | [Upstream releases](https://github.com/MetrolistGroup/innertubex/releases) list v0.8.4, released 2026-10-08 | Existing dependency resolves in the Android build. Provider search → resolve → play was not exercised. | None |
| Compose UI | 1.12.1 | Checked-in version catalog | Build passes; full AndroidX matrix not audited here. | None |
| Material3 | 1.5.0-alpha29 | Checked-in version catalog identifies an alpha | Kept unchanged because the app uses the current API set; stable migration needs a separate compatibility pass. | None |
| Material3 adaptive | 1.4.0-alpha02 | Checked-in version catalog identifies an alpha | Kept unchanged; not part of the Magnet or appearance code path changed here. | None |
| Room / DataStore / KSP | Room 2.8.5 / DataStore 1.2.1 / KSP 2.3.12 | Checked-in version catalog | Existing build succeeds. Schema and dependency migration audit remains open. | None |

The successful local build is evidence that this checkout compiles with the current toolchain; it does not turn an upstream-unlisted combination into a formally certified compatibility matrix. The build emitted existing deprecation warnings, including `android.newDsl=false`; these were not broadened into unrelated migrations.

This is not a full dependency lock, vulnerability, SBOM, license, native-library, or cross-platform audit. No release build was produced.
