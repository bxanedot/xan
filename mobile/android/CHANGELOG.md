# Changelog

## 1.1.4

### Build and dependencies
- Update the Kotlin Gradle plugin to 2.4.21.
- Keep the app's Material 3 dependency declarations aligned on the currently selected 1.5.0 alpha release.

## 1.1.3

### Playback
- Restore YouTube bot-check recovery: refresh visitor data and retry playback once for both signed-in and guest sessions, while preserving account cookies and avoiding retries for unrelated errors.

### Listen Together
- Improve room synchronization by estimating server time from WebSocket ping and pong timestamps.

### About
- Update the developer profile image.

### Android package
- Build one signed universal APK with the app and available native libraries for every packaged Android ABI.

## 1.1.2

### Playback and navigation
- Stop retrying YouTube playback after a clear “sign in to confirm you’re not a bot” response; show the account sign-in guidance instead of repeating requests that cannot succeed as a guest.
- Keep the compact player visible on Home and Library, and hide it on Settings and other destinations.

### Android package
- Build the universal APK with the app and available native libraries for each packaged Android ABI.

## 1.1.1

### Playback and downloads
- Preserve resolved stream URLs, request headers, and byte-range information together so cached audio can resume and seek without reusing stale stream details.
- Route downloads through a fresh stream resolver instead of mixing partial player-cache bytes with the selected download stream.
- Download adaptive video manifests using the track actually playing, including its matching audio stream, and save source metadata before the network job starts.
- Resume interrupted progressive video downloads only when the source validator and returned byte range match; retry transient failures and refresh expired stream URLs without silently changing format.
- Verify completed video containers, audio/video tracks, and duration before publishing files to Android Downloads.
- Show active, failed, and retryable video jobs in Downloads, and reconcile missing saved files when the screen opens.
- Record downloaded video format, codecs, resolution, and byte size; label adaptive manifests separately from file containers.

### About
- Update the BXane profile image to the newly supplied artwork.

## 1.0.1

### Search
- Rename the online search field to “Search XAN” so it no longer implies that every query searches YouTube Music.
- Explain that music-only search excludes videos and point to the Video-only search setting; clarify the empty state when video-only search is active.

## 1.0.0

### Video search, playback, and downloads
- Add a “Video-only search” setting, off by default: off searches music only; on searches videos only.
- Search YouTube and other video services supported by the bundled NewPipe Extractor, and accept supported video pages or direct video-file links.
- Show available stream formats and resolutions, play video in the expanded player, and keep downloads available for direct files and adaptive streams.
- Save direct video files to Android’s Downloads folder; save adaptive HLS/DASH streams for offline playback inside XAN.
- Keep compatibility dependent on each source’s available streams and the codecs supported by the Android device.
- Build optimized APKs for arm64-v8a, armeabi-v7a, x86_64, and x86, alongside a universal fallback APK.
- Detect Android architecture on the download website when the browser reports it, and provide a manual architecture choice with a universal fallback.
- Use a new Android signing identity. This release requires one clean install; later releases can update this installation normally.
- Keep Listen Together available from its own Settings section, with its simple room-code flow and optional advanced controls. Keep Discord and Last.fm as the only entries under Integrations.
- Remove the separate YouTube search fallback setting so music-only search cannot return video results.

### Search and navigation
- Remove the movable selection cover from Explore and Suggestions so it no longer obscures either tab label.

## 0.9.10

### App icons
- Match every selectable Android launcher icon to the Lilac and White icon size across adaptive and legacy launchers.
- Share the launcher inset values between the app resources and Android branding reference assets.

## 0.9.9

### App icons
- Rename the former Original icon to Horizon and remove the Light icon option and its Android launcher resources.
- Give Aurora a green, cyan, and violet palette; make Prism a full spectrum of colors.
- Match every selectable mark and launcher inset to the white icon, then add a soft colored glow.

### Navigation and Home
- Keep the navigation magnet opaque in Solid UI and translucent in Glass UI.
- Make Home mood chips request their own genre endpoint, keep the active chip visible while loading, and report failed filter requests.

## 0.9.8

### Updates
- Keep update checks working when GitHub's unauthenticated Releases API is rate-limited by falling back to the public release feed.
- Preserve release notes and the universal APK download link in the fallback response.
- Add an updater action to choose an existing APK from Android Downloads and open the system installer.

## 0.9.7

### Listen Together
- Keep Listen Together under Settings → Integrations; remove it from the navigation magnet and both player menus.
- Restore optional Cloudflare or Hugging Face server selection, endpoint and username settings, join approval, collaborative queue, volume sync, and smart resync.
- Keep room creation and code-based joining as the default simple flow. Join requests are approved automatically unless manual approval is enabled.

### App icons
- Match the visible logo size in the icon picker and the installed launcher icon across every selectable logo.


## 0.9.6

### Search
- Remove cover artwork and image-backed cards from Search, including Explore and Suggestions; keep the suggestion rows readable as text-first cards.
- Remove the background cover image from Search results too.

### Listen Together and navigation
- Add Listen Together as an optional destination in the navigation magnet and legacy player menu. Room creation and code-based joining keep the same simple flow.
- Remove Shuffle from the navigation magnet and legacy player menu.


## 0.9.5

### Listen Together
- Simplify Android to creating a room and copying its code, or entering a code to join. Use the account name automatically and connect both devices to the same built-in service.
- Join automatically with the room code; remove manual approval, server/name forms, chat, and advanced room controls from the Android interface.
- Keep host-controlled music playback and automatic synchronization through the existing player.
- Fit every room-code character within the card; wrap code boxes on narrow screens or with larger text instead of clipping the right edge.
- Fix creating or joining another room after leaving an earlier session.

### Search
- Remove the background cover from Explore and Suggestions so both tabs use the plain theme background.


## 0.9.4

### Android
- Limit Listen Together provider choices to Cloudflare and Hugging Face, and move saved Render sync endpoints to Cloudflare.
- Remove the Modules settings page; open Listen Together and the PoToken key setting from Integrations.

## 0.9.3

### Android
- Keep automatic update checks and notifications, but download an APK only after the user taps Update.

## 0.9.2

### About and integrations
- Show the lead developer's supplied profile image in About.
- Add direct setup and dashboard links for Cloudflare, Render, and Hugging Face in Listen Together.

### Maintenance
- Remove duplicated Kotlin imports and align project source licensing with GPL-3.0-only; third-party dependency terms remain separate.

## 0.9.1

### App icon
- Normalized every selectable logo to the default white mark's visible size.
- Show the selected logo in Appearance settings and match the picker preview size.
- Load the lead developer photo from the bxanedot GitHub profile, with an up-to-date bundled fallback.

### Search and appearance
- Keep only Explore and Suggestions as Search tabs; Explore is restored for users who had Album selected.
- Make the Search tab selector follow Solid UI: solid surfaces when enabled, and live backdrop-sampled liquid glass when disabled.
- Keep glass effects based on the content currently behind each surface, using the device renderer rather than a stored screenshot.

### About
- Put the GitHub source-code link under App info.

### Listen Together
- Restored the older Hugging Face Docker Space as a provider alongside Cloudflare, Render, and custom servers.
- Save the Hugging Face endpoint separately and keep compatibility with the older room protocol.

## 0.9.0

### Playback
- Added InnerTubeX stream extraction with the existing YouTube resolver as a fallback.
- Applied extractor request headers and bounded range settings to streaming and downloads.
- Refresh the extractor's cipher configuration after expired stream URLs.
- Kept the existing lossless streaming and single universal APK configuration.

### About and Support
- Added separate Patreon, Ko-fi, and guns.lol links with matching icons.
- Updated the occasional support prompt to open Ko-fi.

### Privacy
- Added a FOSS mode switch under Settings → Privacy. It is off by default so Cast is available in the all-features APK; enabling it disables Play Services integrations such as Cast.

## 0.8.1

### Navigation
- Hide the Magnet on Settings subpages while keeping it visible on the main Settings screen.

### Downloads
- Build one universal APK containing all supported device architectures.

## 0.8.0

### Listen Together
- Added Cloudflare and Render server choices and improved playback clock synchronization and recovery.

### Downloads
- Kept smaller ARM64 and x86_64 APKs for the in-app updater, which chooses the best compatible build and uses the universal APK as a fallback.
- Combined the open-source app features and optional Google Play Services Cast support in the same release.

## 0.7.14

### Equalizer
- Show all ten Advanced EQ bands together in a two-row layout on Android.

## 0.7.13

### Listen Together
- Re-enabled the Listen Together manager in the app so the Modules and expanded player shortcuts open the working feature.
- Added server setup and connect directly to the Listen Together screen, with validation for WebSocket URLs.
- Redesigned Listen Together with a compact branded header, prominent connection card, and grouped room controls.
- Clarified that users need to supply a sync server address because XAN does not currently operate a public sync server.

## 0.7.12

### App
- Added BiniLyrics and Unison providers, parallel lyric lookup, and richer TTML word timing.
- Added a Wi-Fi-only Canvas option and persistent History search with Local/Remote history selection.
- Removed the duplicate small Canvas video from Player V2.
- Restored the Listen Together room screen in Settings and the main navigation toggle; validate custom WebSocket server URLs before connecting.
- Added Listen Together shortcuts to Modules and the expanded player's More actions.
- Fixed artist downloads and artist playlist creation to exclude tracks credited to unrelated artists.
- Enlarged the About logo and app launcher marks by 20% while leaving the installer logo size unchanged.

## 0.7.11

### App and installer logos
- Set the supplied white XAN ring as the launcher and installable app logo.
- Reduced the installer icon mark while keeping the launcher logo at its normal size.
- Kept the gold XAN mark transparent inside the app, including the Magnet.

## 0.7.10

### Magnet logo
- Removed the dark square tile behind the XAN logo in the Magnet menu.
- The Magnet now shows only the transparent logo mark in both stacked and semicircle layouts.

## 0.7.9

### Original Magnet
- Restored the original labeled menu as the default, with its eight opening animations and centered Corkscrew, Bloom, and Accordion layouts.
- Switched existing installs to the original layout once on update.
- Kept the semicircle available in Appearance → Magnet layout; later selections are preserved.

## 0.7.8

### Navigation
- Added coordinated slide and fade motion to screen pushes, including a parallax shift for the outgoing screen.
- Kept artwork-to-screen morphs and gesture-driven back transitions working with their existing motion.

### Magnet
- Restored the semicircle Home logo to the 20 dp size used in 0.6.10.

## 0.7.6

### App icon
- Restored the Gold, Original, Light, and themed launcher marks to their normal size, and matched the icon picker previews.

### Downloads and updates
- Added smaller APKs for arm64 phones and x86_64 devices, plus a universal fallback.
- Compressed native libraries inside the APK downloads to reduce download size.
- Updated the in-app updater to prefer the APK built for the phone's supported CPU architecture.

## 0.7.5

### About screen
- Moved Discord into Community and Changelog into Developer.
- Used the supplied Gold PNG for the About screen logo.

## 0.7.4

### Project links
- Pointed the app's Lead Developer link to the XAN repository at `bxanedot/xan`.

## 0.7.3

### Semicircle Magnet
- Reduced the Home XAN logo tile from 20 dp to 16 dp to better match the compact symbols in XAN Desktop's Magnet, while keeping the 52 dp touch target.

## 0.7.2

### App icon and media widget
- Made the XAN mark 60% smaller on the home screen across Gold, Original, Light, round, and monochrome launcher icons.
- Matched the App icon picker previews to the smaller launcher marks. The idle media widget uses the smaller app icon while album artwork remains unchanged.

## 0.7.1

### Installer icon
- Made the XAN ring 60% smaller inside the APK installer icon on adaptive, round, legacy, and monochrome icon resources.

### Project links
- Updated the Android app's repository, release, API, and raw content links to `bxanedot/xan`.

## 7.0.0

### Installer icon
- Made the XAN ring 60% smaller inside the APK installer icon on adaptive, round, legacy, and monochrome icon resources.
- Kept the installer icon's existing dark backgrounds.

## 0.6.10

### In-app logo
- Placed the selected XAN ring inside a dark gray square matching the supplied reference throughout the app, including the semicircle Magnet.
- Kept the standalone ring artwork for the installed launcher and APK installer icons.
- Kept the App icon picker previews showing the installed icon appearance.

## 0.6.9

### Magnet
- Made every icon in the semicircle Magnet smaller, including the Home logo and open/close symbol, while keeping the existing tap targets and spacing.

### App icon
- Added 2D **Gold** as the default XAN logo, based on the installed wavy ring with a subtle gold sheen and no bevel or raised edges.
- Restored an **Appearance → App icon** picker with **Gold**, **Original**, and **Light** previews.
- Applied the chosen logo to the home screen launcher and shared in-app XAN marks, including the Magnet.
- Kept the existing launcher alias stable across updates and preserved previous Light selections until a different icon is chosen.

## 0.6.8

### Playback
- Fixed playback getting stuck after a long network outage. Recovery now waits for connectivity without consuming retry attempts while offline.
- Fixed AudioTrack and decoder recovery losing play/pause intent. Playing tracks resume after recovery, and a user pause during recovery stays paused.
- Cancelled pending recovery when switching tracks or replacing the queue, preventing a delayed retry from restarting a newly selected song.
- Kept lossless stall recovery on the active track and preserved the user's playback intent.
- Cancelled service recovery jobs during shutdown so they cannot act on a released player.

### Navigation
- Made the semicircle Magnet layout the default. Navigation and quick actions fan out around the Magnet, with mirrored placement on the left and right.
- Added **Appearance → Magnet layout** to switch between **Semicircle** and the existing **Vertical stack** above the Magnet.
- Preserved accessible action names, the selected destination indicator, spring animations, and tap-away/back dismissal.
- Very small split-screen windows use the scrolling stack to keep all actions reachable.

### Release
- Version: **0.6.8**
- Added playback reconnection and Magnet layout regression tests to the signed release build.

## 0.6.7

- Reduced the installed launcher mark by about 20% and the APK installer mark by 50% across adaptive, themed, and legacy icons.

## 0.6.6

### Navigation
- Added **Appearance → Navigation style** with two choices:
  - **Magnet** (default): uses the XAN navigation magnet.
  - **Basic / Legacy**: restores the classic floating navigation buttons.
- The Magnet is shown only when Magnet navigation is selected.
- The Magnet position preference continues to support bottom-left or bottom-right placement.

### Player
- Fixed the mini-player disappearing in the collapsed state. A downward swipe still dismisses it until the track changes.
- Widened the Magnet-mode mini-player to fill the space beside the Magnet, leaving a three-pixel gap on either side setting.
- Hide the Magnet while the full-screen player is open and restore it when the player collapses.
- Magnet mode now centers the mini-player vertically with the 56dp Magnet button.
- In Magnet mode the mini-player is anchored to the side opposite the Magnet with a reserved touch zone, preventing overlap.
- Swiping the mini-player downward dismisses it in both Magnet and Basic / Legacy styles.
- Basic / Legacy mode restores the 0.6.5 player spacing and placement alongside the classic navigation buttons.
- Switching navigation style changes the navigation and player layout together to avoid mixed layouts.

### App icon
- Split launcher and installer icon resources so their sizes can be tuned independently.
- Balanced the installed launcher and Play Protect / installer icon sizes across adaptive and legacy icons.

### Release
- Version: **0.6.6**
