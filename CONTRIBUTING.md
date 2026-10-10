# Let's build XAN together 🎧

Welcome! We’re glad you’re here. XAN gets better through all kinds of contributions: a playback fix, a helpful screenshot, a translation, a website tweak, a test, or a clearer sentence in the docs. You don’t need to know the whole codebase to help. Pick one small thing and we’ll take it from there.

## Not sure where to start?

Have a look through [open issues](https://github.com/bxanedot/xan/issues) and [pull requests](https://github.com/bxanedot/xan/pulls). You might find a task that sounds fun, or a bug you already know how to fix. For a bigger idea, opening an issue first gives everyone a chance to talk it through. A typo or small fix can usually go straight to a pull request.

Here’s the quick path:

1. Find the folder that owns the part you want to change.
2. Create a branch, which is your own little workspace for the change.
3. Make the change and run the checks for that area.
4. Open a pull request and tell us what you did. We’ll review it together.

## A map of the project

| Folder | What you’ll find there | Where things stand |
| --- | --- | --- |
| [`mobile/android/`](mobile/android/) | Android app source, Gradle build, and Android branding. | The main native app; actively developed. |
| [`mobile/ios/`](mobile/ios/) | iOS project and iOS branding. | Organized for future work; native app source is not implemented yet. |
| [`desktop/linux/`](desktop/linux/), [`desktop/windows/`](desktop/windows/), [`desktop/macos/`](desktop/macos/) | Platform-specific desktop projects and branding. | Organized for future work; native desktop apps are not implemented yet. |
| [`web/`](web/) | Next.js website and its assets. | Website is implemented. |
| [`backend/listen-together/`](backend/listen-together/) | Cloudflare Worker and Node.js WebSocket sync server. | Backend implementations and tests are included. |
| [`docs/`](docs/) | Project, licensing, and UI docs, including screenshots. | Shared documentation. |
| [`tools/`](tools/) | Repository scripts and fixtures. | Development helpers. |

Keep platform-specific code and branding with that platform. Put material shared across platforms in `docs/` or the repository root. The website lives in `web/`.

## Get a copy and make a branch

Fork the repository on GitHub, then clone your fork. These commands start a branch called `fix/short-description`; feel free to name it after your change, such as `feature/lyrics-cache` or `docs/contributing`.

```sh
git clone https://github.com/YOUR_USERNAME/xan.git
cd xan
git remote add upstream https://github.com/bxanedot/xan.git
git switch -c fix/short-description
```

## Run the checks for your change

You don’t have to build the whole project for a small docs or website change. Choose the section for the folder you edited. If a check needs a tool or device you don’t have, just say so in the pull request; that helps reviewers understand what was checked.

### Android 📱

You’ll need Android Studio, JDK 21, and the Android SDK components requested by the project.

**Windows PowerShell** — run from the repository root:

```powershell
Set-Location mobile/android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

**macOS or Linux** — run from the repository root:

```sh
cd mobile/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

For playback, permissions, notifications, or device-specific changes, try the affected flow on an emulator or phone if you can. For visual changes, check a couple of screen sizes and text scaling too.

### Website 🌐

Install Node.js 24 or newer. From the repository root:

```sh
cd web
npm ci
npm run typecheck
npm run build
```

### Listen Together 🎶

Install Node.js 24 or newer. From the repository root:

```sh
cd backend/listen-together
npm ci
npm test
docker build -t xan-listen-together .
```

The Docker command checks that the Node.js server image builds. The [Listen Together README](backend/listen-together/README.md) explains Cloudflare and Render; the [Hugging Face guide](backend/listen-together/README.huggingface.md) covers Docker Spaces. The Android app has configured Cloudflare and Render endpoints. Hugging Face needs a deployed Space URL before it can be used as a working endpoint.

### iOS and desktop 🍎🖥️

These folders are ready for platform-specific contributions, but native iOS, Linux, Windows, and macOS apps do not have buildable projects yet. If you start one of those clients, please include the actual build and test instructions with it.

## A few things that make reviews easier

- Keep a change focused. It’s easier for everyone to review one clear improvement at a time.
- Follow the patterns used nearby, and add dependencies only when the change needs them.
- For interface changes, consider light and dark themes, accessibility, touch targets, loading and error states, and phone and desktop sizes where they apply.
- Update the docs when setup, commands, behavior, or platform support changes.
- Don’t commit generated build files, downloaded music, local configuration, or credentials.

Before you commit, take a quick look at `git status` and `git diff`. That’s a handy way to catch unrelated files or a secret before it leaves your computer.

Short commit messages help everyone scan the history. For example:

```text
fix: restore queue after reconnect
feat: add playlist sorting option
docs: explain Listen Together setup
```

## Ready to open a pull request? 🚀

Open it against `main`. There’s no magic format; just tell reviewers:

- What changed and what problem it helps with.
- Which tests or commands you ran.
- What you checked on a device or emulator, if relevant.
- Anything you’d like reviewers to pay special attention to.

Screenshots are especially helpful for visual changes. If you get feedback, keep updating the same branch so the conversation and fix stay together. Thanks for working through the review with us.

## Android releases (for release contributors)

The Android version name and update code live in [`mobile/android/version.properties`](mobile/android/version.properties). Set `XAN_VERSION` to the release name. Give each published APK a unique `XAN_VERSION_CODE` above the previous published code—usually the next number. If that number has already been used and can’t be reused, choose the next unused number instead. Android needs a higher code to install an update over an existing copy.

The GitHub release workflow builds one universal APK named `xan.apk`. Release signing keys and `local.properties` must stay on your computer or in the configured GitHub Actions secrets; please never add them to a commit. The workflow’s secret names are listed in [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Security and licensing

If something looks like a security vulnerability, please don’t add the technical details to a public issue or pull request. The [security guide](SECURITY.md) explains the current reporting options and how to test safely.

XAN's original source is licensed under [GPL-3.0-only](LICENSE). Third-party components may have their own terms; see [`docs/LICENSING.md`](docs/LICENSING.md).

Thanks for being part of XAN. Every thoughtful fix, good question, and clear explanation makes it easier for the next person to contribute too. 💛
