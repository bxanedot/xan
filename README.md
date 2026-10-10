<div align="center">

# XAN 🎵

### Your music. Your library. Your way.

**Fast · Fluid · Personal**

<br>

<a href="https://github.com/bxanedot/xan/releases/latest/download/xan.apk">
  <img src="https://img.shields.io/badge/⚡%20DOWNLOAD-LATEST%20APK-2ea44f?style=for-the-badge&logo=android" alt="Download Latest APK">
</a>

<br><br>

<a href="https://github.com/bxanedot/xan/releases/latest">
  <img src="https://img.shields.io/github/v/release/bxanedot/xan?style=for-the-badge&label=LATEST" alt="Latest Release">
</a>
<a href="https://github.com/bxanedot/xan/releases">
  <img src="https://img.shields.io/github/downloads/bxanedot/xan/total?style=for-the-badge" alt="Downloads">
</a>
<a href="https://github.com/bxanedot/xan">
  <img src="https://img.shields.io/github/stars/bxanedot/xan?style=for-the-badge" alt="Stars">
</a>
<a href="https://github.com/bxanedot/xan/blob/main/LICENSE">
  <img src="https://img.shields.io/github/license/bxanedot/xan?style=for-the-badge" alt="License">
</a>

<br><br>

<a href="https://github.com/bxanedot/xan/releases/latest">Download</a>
 ·  <a href="#-features">Features</a>
 ·  <a href="#-downloads">Downloads</a>
 ·  <a href="#-building">Build</a>
 ·  <a href="#-contributing">Contribute</a>
 ·  <a href="#-security">Security</a>

</div>

---

## 🎧 What is XAN?

**XAN** is an Android music player designed to make your music library feel **fast, fluid, and personal**.

Built around your listening experience, XAN brings playback, discovery, lyrics, playlists, downloads, visual customization, and powerful integrations together in one place.

> **Your music. Your library. Your way.**

## Platforms

| Platform | Status | Project folder |
| --- | --- | --- |
| Android | Available | [`mobile/android/`](mobile/android/) |
| iOS/iPadOS | Shared UI and Swift host in progress; Apple verification pending | [`mobile/ios/`](mobile/ios/) |
| Linux desktop | Packages configured; target build/runtime verification pending | [`desktop/linux/`](desktop/linux/) |
| Windows desktop | Local x64 MSI/app built; full parity pending | [`desktop/windows/`](desktop/windows/) |
| macOS desktop | Intel/Apple Silicon builds configured; target verification pending | [`desktop/macos/`](desktop/macos/) |
| Website | Available | [`web/`](web/) |
| Listen Together service | Cloudflare Workers or Hugging Face Spaces | [`backend/listen-together/`](backend/listen-together/) · [Cloudflare setup](backend/listen-together/README.md#cloudflare-worker) · [Hugging Face setup](backend/listen-together/README.huggingface.md) |

---

# ✨ Features

## 🎵 Music

* 🎶 Local music playback
* 🌐 Online music sources
* ▶️ Background playback
* ⏭️ Next / previous controls
* 🔀 Shuffle
* 🔁 Repeat
* 📋 Queue management
* 🎧 Bluetooth support
* 🎛️ Headset and media-button controls
* 🔒 Android lock-screen controls
* 🔔 Notification media controls

---

## 📚 Your Library

Keep everything you listen to in one place.

* 🎵 Songs
* 💿 Albums
* 👤 Artists
* 📑 Playlists
* ❤️ Liked songs
* 📋 Queue
* 🔎 Search
* 🕘 Listening history
* 🌟 Music discovery

---

## 📝 Lyrics

Follow along while you listen.

* 📝 Synced lyrics
* 📄 Plain lyrics
* 🌍 Lyrics translation
* 🔌 Multiple lyric providers
* 🎤 Lyrics-focused playback experience

---

## 🔎 Discover

Find music without leaving XAN.

* 🔍 Search songs
* 👤 Search artists
* 💿 Search albums
* 🎵 Discover tracks
* 🎤 Shazam integration
* 🌐 Online music discovery
* 🎨 Artist and album visuals

---

# 📥 Downloads

### One-click music downloads.

XAN makes supported downloads simple.

### 🎵 Songs

Download individual tracks directly from supported sources.

### 💿 Albums

Save supported albums without downloading every track individually.

### 👤 Artists

Open an artist and download supported releases directly from the artist experience.

### 📚 Playlists

Save supported playlists for offline listening.

### ⚡ One Click

**Find → Download → Listen.**

No complicated workflow.

---

# 🎨 Make It Yours

XAN is built to feel personal.

Customize your listening experience with:

* 🎨 Custom appearance
* 🖼️ Dynamic artwork
* 👤 Artist visuals
* 🎬 Video / canvas-style visuals
* 🌈 Personalized player experience
* ⚙️ Playback customization

Your player should feel like **your** player.

---

# 🧭 Navigation Layouts

XAN offers two ways to move around the app. **Magnet** opens a floating shortcut menu, while **Legacy** keeps the classic bottom navigation bar.

### Magnet

<img src="docs/screenshots/magnet-library.jpg" width="320" alt="XAN Library with the Magnet launcher">

### Legacy

<img src="web/public/screenshots/07.jpg" width="320" alt="XAN Library with the classic bottom navigation bar">

### About and app info

The app's **Settings → About** screen shows release **0.9.2**, Android version code **7000020**, the install date recorded on that device, the GPL-3.0-only project license, GitHub and website links, and direct links to GitHub, guns.lol, Patreon, and Ko-fi. **Settings → Privacy** includes a FOSS mode switch that disables Google Play Services features such as Cast; it is off by default, so the single APK opens with all features available. The website's About section repeats the release details and explains the same in-app information below them.

---

# 🔌 Integrations

XAN connects with services you already use.

* 🟢 Spotify playlist importing
* 📊 Last.fm
* 🎤 Shazam
* 💬 Discord integration
* 📝 External lyrics providers
* 🎵 External music sources

Some integrations may require network access, an account, regional availability, or user-provided API credentials.

---

# ⚡ Built to Feel Fast

XAN is designed around a simple principle:

> **The interface should get out of the way of the music.**

Search it.

Play it.

Download it.

Queue it.

Customize it.

Listen.

---

# 🛠️ Building

## Requirements

* Android Studio
* JDK 21 or newer
* Android SDK
* Git
* Internet access for Gradle dependencies

## Clone

```bash
git clone https://github.com/bxanedot/xan.git
cd xan
```

Open the `mobile/android/` folder in Android Studio and allow Gradle to synchronize.

## Build

### Windows

```powershell
cd mobile/android
.\gradlew.bat :app:assembleDebug
```

### Linux / macOS

```bash
cd mobile/android
./gradlew :app:assembleDebug
```

The debug APK is generated under:

```text
mobile/android/app/build/outputs/apk/debug/
```

---

# 📁 Project Structure

```text
xan/
├── mobile/
│   ├── android/              # Android app, modules, branding, and Gradle project
│   └── ios/                  # iOS project and platform branding
├── desktop/
│   ├── shared/               # Compose Desktop app, services, and packaging
│   ├── linux/                # Linux desktop project and branding
│   ├── windows/              # Windows desktop project and branding
│   └── macos/                # macOS desktop project and branding
├── web/                      # Next.js website
├── backend/listen-together/  # WebSocket synchronization service
├── docs/                     # Repository documentation
├── tools/                    # Development and maintenance tools
└── .github/                  # Shared automation and CI workflows
```

See [Repository guide](docs/REPOSITORY.md) for directory ownership and build commands.

---

# 🤝 Contributing

XAN is open source.

Contributions of all kinds are welcome.

### You can help with:

* 🐛 Bug fixes
* ⚡ Performance improvements
* 🎨 UI / UX improvements
* 🎵 Playback improvements
* 📥 Download improvements
* 🔌 New integrations
* 🌍 Translations
* 🧪 Testing
* 📚 Documentation
* 🧹 Refactoring
* 💡 Ideas and improvements

Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) before contributing.

---

# 🔐 Security

Security matters.

**Please do not publicly disclose security vulnerabilities through GitHub Issues.**

If you discover a vulnerability that could affect XAN or its users, please report it privately.

### [🔒 Read the Security Policy](SECURITY.md)

### [GitHub Security](https://github.com/bxanedot/xan/security)

Security reports should contain enough information to understand and reproduce the issue.

Please never include passwords, private API keys, signing keys, or other sensitive credentials in a report.

---

# 📜 License

XAN's original source code is distributed under **GPL-3.0-only**.

**SPDX-License-Identifier: GPL-3.0-only**

See [`LICENSE`](LICENSE) for the complete license text and [licensing notes](docs/LICENSING.md) for third-party components included by the app.

---

# 🌐 Community & Links

<div align="center">

<a href="https://github.com/bxanedot/xan">
  <img src="https://img.shields.io/badge/GitHub-Source-181717?style=for-the-badge&logo=github" alt="GitHub">
</a>

<a href="https://github.com/bxanedot/xan/releases">
  <img src="https://img.shields.io/badge/Releases-Download-181717?style=for-the-badge&logo=github" alt="Releases">
</a>

<a href="https://guns.lol/bxane">
  <img src="https://img.shields.io/badge/bxane-Profile-5865F2?style=for-the-badge" alt="bxane">
</a>

<a href="https://ko-fi.com/bxane">
  <img src="https://img.shields.io/badge/Ko--fi-Support-FF5E5B?style=for-the-badge&logo=ko-fi" alt="Ko-fi">
</a>

<a href="https://www.patreon.com/bxane">
  <img src="https://img.shields.io/badge/Patreon-Support-F96854?style=for-the-badge&logo=patreon" alt="Patreon">
</a>

</div>

---

<div align="center">

# 🎵 XAN

### Fast. Fluid. Personal.

**Built for people who care about their music.**

<br>

Made with ♥ by **bxane**

</div>
