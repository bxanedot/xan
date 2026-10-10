# Android platform support

This report reflects the Android manifest and media-session code in this repository. The app was built and its JVM unit tests were run on a Windows development environment. No Android device, Android Auto head unit, Automotive emulator, or Android TV device was attached, so hardware and UI navigation behavior have not been certified here.

## Phones and tablets

These are the primary Android targets. The manifest declares touchscreen as optional, and the app provides the full Compose interface, media playback service, scoped-storage download publishing on Android 10 and newer, and app-owned Downloads storage on Android 8 and 9. A physical-device playback and offline-download pass is still needed to validate vendor-specific behavior.

## Android Auto

The app declares a Media3 `MediaLibraryService` and the Android Auto media-app descriptor (`res/xml/automotive_app_desc.xml`). The media library callback supports browsing, search, playback resumption, and queue resolution. It filters items marked as video from browse children, single-item lookup, and search results so video-only results are not offered through those car browsing surfaces. Download and playback changes are covered by the Android unit/build checks, but no Android Auto emulator or vehicle was available for an end-to-end test.

## Android Automotive OS

The same media-library service and automotive media descriptor provide the repository's Automotive integration. The repository does not contain a separate Automotive activity or app module. No AAOS emulator or head unit was available, so installation, media browsing, and driver-distraction policy behavior on an actual Automotive host remain unverified.

## Android TV / Google TV

The manifest marks Leanback and touchscreen as optional, supplies a TV banner, and adds Leanback launcher categories to the launcher entries. The app does not contain a dedicated TV interface or a complete D-pad/focus-navigation implementation; keyboard/focus helpers exist in parts of the phone UI only. Treat TV launch visibility as present, but do not treat the app as verified or fully TV-optimized until a TV-specific navigation pass is completed.

## Other project targets

The repository also contains iOS, web, and desktop projects. This Android media-download change does not change or validate those targets.
