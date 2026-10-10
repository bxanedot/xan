"use client";

import { useEffect, useState } from "react";
import { XAN_LINKS } from "@/lib/links";
import type { InstallerKey, ReleaseCatalog } from "@/lib/github";
import { ANDROID_ABIS, detectAndroidAbiFromBrowser, isAndroidDownloadAbi, type AndroidDownloadAbi } from "@/lib/android-abi";
import { LanguageSelect, T, useLanguage } from "@/lib/i18n";
import {
  detectPlatform,
  INSTALLER_PLATFORMS,
  platformLabel,
  type InstallerPlatform,
} from "@/lib/platform";

const platformDescriptions: Record<InstallerPlatform, string> = {
  android: "Install the latest XAN release on your Android device.",
  macos: "Download the latest XAN installer for macOS.",
  ios: "An iOS version is in development.",
  windows: "Download the latest XAN installer for Windows.",
  linux: "Download the latest XAN installer for Linux.",
};

function getInstaller(catalog: ReleaseCatalog | null, platform: InstallerPlatform, abi: AndroidDownloadAbi) {
  if (platform === "android") {
    return catalog?.androidVariants[abi] ?? catalog?.androidVariants.universal ?? catalog?.installers.android ?? null;
  }
  if (platform === "windows" || platform === "linux" || platform === "macos") {
    return catalog?.installers[platform as InstallerKey] ?? null;
  }
  return null;
}

function formatSize(bytes: number | null) {
  if (!bytes) return null;
  const megabytes = bytes / 1024 / 1024;
  return `${megabytes.toFixed(megabytes >= 10 ? 0 : 1)} MB`;
}

function PlatformGlyph({ platform }: { platform: InstallerPlatform }) {
  const iconAssets: Partial<Record<InstallerPlatform, string>> = {
    android: "/installer-icons/android.png",
    ios: "/installer-icons/ios.png",
    linux: "/installer-icons/linux.png",
  };
  const iconAsset = iconAssets[platform];
  if (iconAsset) {
    return (
      <span
        className="platform-glyph-mask"
        style={{ maskImage: `url(${iconAsset})`, WebkitMaskImage: `url(${iconAsset})` }}
        aria-hidden="true"
      />
    );
  }

  if (platform === "ios" || platform === "macos") {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" stroke="none" d="M18.7 19.5c-.83 1.24-1.71 2.45-3.09 2.47-1.35.02-1.78-.79-3.32-.79s-2.02.77-3.3.82c-1.32.05-2.32-1.34-3.16-2.58-1.72-2.49-3.03-7.03-1.27-10.09.88-1.53 2.43-2.5 4.12-2.53 1.28-.02 2.49.87 3.27.87.77 0 2.25-1.07 3.8-.91 0 0 1.99.08 3.29 1.68-.09.06-1.97 1.15-1.95 3.43.03 2.72 2.38 3.63 2.4 3.64-.02.06-.37 1.3-1.22 2.51ZM13.19 3.48c.7-.81 1.86-1.43 2.8-1.48.12 1.1-.32 2.22-1.01 3.03-.67.82-1.76 1.45-2.83 1.36-.14-1.06.39-2.18 1.04-2.91Z" /></svg>;
  }
  if (platform === "windows") {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 5 8-1v7H3V5Zm10-1 8-1v9h-8V4ZM3 13h8v7l-8-1v-6Zm10 0h8v9l-8-1v-8Z" /></svg>;
  }
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <ellipse cx="12" cy="13.2" rx="7.2" ry="9" fill="currentColor" stroke="none" />
      <ellipse cx="12" cy="15" rx="4.2" ry="5.7" fill="var(--panel)" stroke="none" />
      <circle cx="9.4" cy="7.4" r=".7" fill="var(--panel)" stroke="none" />
      <circle cx="14.6" cy="7.4" r=".7" fill="var(--panel)" stroke="none" />
      <path d="m10 9.1 2 1.6 2-1.6-2-1.2-2 1.2Z" fill="#f2b84b" stroke="none" />
      <path d="m7.2 19.3 2.3 1.2 1.2-.8m4.1 0 1.7.8 1.1-1.2" fill="none" stroke="#f2b84b" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function PlatformDownloads() {
  const { t } = useLanguage();
  const [selected, setSelected] = useState<InstallerPlatform>("android");
  const [release, setRelease] = useState<ReleaseCatalog | null>(null);
  const [releaseUnavailable, setReleaseUnavailable] = useState(false);
  const [androidAbi, setAndroidAbi] = useState<AndroidDownloadAbi>("universal");

  useEffect(() => {
    const queryPlatform = new URLSearchParams(window.location.search).get("platform");
    const queryAbi = new URLSearchParams(window.location.search).get("abi");
    const detected = detectPlatform(navigator.userAgent, navigator.platform, navigator.maxTouchPoints);
    const initial = INSTALLER_PLATFORMS.find((platform) => platform === queryPlatform)
      ?? (detected === "other" ? "android" : detected);
    setSelected(initial);
    window.history.replaceState(null, "", `/download?platform=${initial}`);

    if (isAndroidDownloadAbi(queryAbi)) {
      setAndroidAbi(queryAbi);
      if (initial === "android") {
        window.history.replaceState(null, "", `/download?platform=android&abi=${queryAbi}`);
      }
    } else {
      void detectAndroidAbiFromBrowser(
        (navigator as Navigator & { userAgentData?: { getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string; bitness?: string }> } }).userAgentData,
        navigator.userAgent,
      ).then((abi) => {
        setAndroidAbi(abi);
        if (initial === "android") {
          window.history.replaceState(null, "", `/download?platform=android&abi=${abi}`);
        }
      });
    }

    const controller = new AbortController();
    fetch("/api/release", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error("release lookup failed");
        return (await response.json()) as ReleaseCatalog;
      })
      .then(setRelease)
      .catch((error) => {
        if (error?.name !== "AbortError") setReleaseUnavailable(true);
      });

    return () => controller.abort();
  }, []);

  const choosePlatform = (platform: InstallerPlatform) => {
    setSelected(platform);
    window.history.replaceState(null, "", `/download?platform=${platform}${platform === "android" ? `&abi=${androidAbi}` : ""}`);
  };

  const selectedInstaller = getInstaller(release, selected, androidAbi);
  const availableAndroidAbis = ANDROID_ABIS.filter((abi) => Boolean(release?.androidVariants[abi]));
  const chooseAndroidAbi = (abi: AndroidDownloadAbi) => {
    setAndroidAbi(abi);
    window.history.replaceState(null, "", `/download?platform=android&abi=${abi}`);
  };

  useEffect(() => {
    if (selected === "android" && release && androidAbi !== "universal" && !release.androidVariants[androidAbi]) {
      setAndroidAbi("universal");
    }
  }, [androidAbi, release, selected]);
  const selectedSupported = selected === "android" || selected === "windows" || selected === "linux" || selected === "macos";
  const selectedStatus = getInstaller(release, selected, androidAbi)
    ? "Available now"
    : !release && !releaseUnavailable
      ? "Checking availability…"
      : selectedSupported
        ? "Not available yet"
        : "In development";

  return (
    <main className="download-page">
      <header className="download-page-header">
        <a className="download-brand" href="/" aria-label="XAN home">
          <span aria-hidden="true" className="xan-brand-mark" />
          <span>XAN</span>
        </a>
        <div className="flex items-center gap-3"><LanguageSelect /><a className="download-back-link" href="/#top"><T text="Back to XAN" /> <span aria-hidden="true">↗</span></a></div>
      </header>

      <section className="download-page-content" aria-labelledby="download-page-title">
        <p className="eyebrow"><T text="XAN INSTALLERS" /></p>
        <h1 id="download-page-title"><T text="Choose your device." /></h1>
        <p className="download-page-intro">
          <T text="Choose a platform to see the latest installer published for it." />
        </p>

        <div className="platform-picker" role="group" aria-label={t("Choose your device platform")}>
          {INSTALLER_PLATFORMS.map((platform) => {
            const available = Boolean(getInstaller(release, platform, androidAbi));
            const active = selected === platform;
            return (
              <button
                className={`platform-option${active ? " platform-option-active" : ""}`}
                type="button"
                key={platform}
                aria-pressed={active}
                onClick={() => choosePlatform(platform)}
              >
                <span className="platform-option-icon"><PlatformGlyph platform={platform} /></span>
                <span className="platform-option-copy">
                  <strong>{platformLabel(platform)}</strong>
                  <small>{t(available ? "Available now" : platform === "ios" ? "In development" : releaseUnavailable ? "Unavailable" : release ? "Not available yet" : "Checking availability…")}</small>
                </span>
                <span className={`platform-option-indicator${available ? " platform-option-indicator-ready" : ""}`} aria-hidden="true" />
              </button>
            );
          })}
        </div>

        <section className="platform-detail" aria-live="polite" aria-labelledby="platform-detail-title">
          <div className="platform-detail-heading">
            <span className="platform-detail-icon"><PlatformGlyph platform={selected} /></span>
            <div>
              <p className="eyebrow"><T text="SELECTED PLATFORM" /></p>
              <h2 id="platform-detail-title">{platformLabel(selected)}</h2>
            </div>
          </div>
          <p className="platform-detail-description"><T text={platformDescriptions[selected]} /></p>

          {selected === "android" && release && (
            <div className="android-abi-picker">
              <label htmlFor="android-abi">APK architecture</label>
              <select id="android-abi" value={androidAbi} onChange={(event) => chooseAndroidAbi(event.target.value as AndroidDownloadAbi)}>
                <option value="universal">Universal · works on supported Android devices</option>
                {availableAndroidAbis.map((abi) => <option value={abi} key={abi}>{abi}</option>)}
              </select>
              <p>{androidAbi === "universal" ? "Using the compatibility APK." : `Detected ${androidAbi}; this smaller APK is selected for your device.`}</p>
            </div>
          )}

          {selectedInstaller ? (
            <>
              <a className="download-button platform-download-button" href={`/api/download?platform=${selected}${selected === "android" ? `&abi=${androidAbi}` : ""}`}>
                <span><T text={selected === "android" ? "Download Android APK" : selected === "windows" ? "Download Windows installer" : selected === "macos" ? "Download macOS installer" : "Download Linux installer"} /></span>
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0 5-5m-5 5-5-5M5 21h14" /></svg>
              </a>
              <p className="platform-release-meta">
                {`${selectedInstaller.version}${formatSize(selectedInstaller.size) ? ` · ${formatSize(selectedInstaller.size)}` : ""} · ${selectedInstaller.filename}`}
              </p>
            </>
          ) : (
            <div className="platform-coming-soon" role="status">
              <span className="platform-status-dot" aria-hidden="true" />
              <span>{t(selectedStatus === "In development" ? "Selected platform installer is in development" : selectedStatus === "Checking availability…" ? selectedStatus : releaseUnavailable ? "The latest release could not be loaded. You can still view it on GitHub." : "No installer for this platform is available in the latest release.")}</span>
            </div>
          )}
          {releaseUnavailable && <a className="platform-release-link" href={XAN_LINKS.releases} target="_blank" rel="noreferrer"><T text="View releases on GitHub" /></a>}
        </section>

        <p className="download-page-footnote">
          <T text="Looking for a specific build?" /> <a href={XAN_LINKS.releases} target="_blank" rel="noreferrer"><T text="GitHub releases page" /></a> <T text="lists every published installer." />
        </p>
      </section>
    </main>
  );
}
