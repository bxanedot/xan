"use client";

import { useEffect, useState } from "react";
import { detectPlatform, platformLabel, type DetectedPlatform } from "@/lib/platform";
import { detectAndroidAbiFromBrowser, type AndroidDownloadAbi } from "@/lib/android-abi";
import { T, useLanguage } from "@/lib/i18n";

export function DownloadButton() {
  const { t } = useLanguage();
  const [platform, setPlatform] = useState<DetectedPlatform>("other");
  const [androidAbi, setAndroidAbi] = useState<AndroidDownloadAbi>("universal");

  useEffect(() => {
    setPlatform(detectPlatform(navigator.userAgent, navigator.platform, navigator.maxTouchPoints));
    void detectAndroidAbiFromBrowser(
      (navigator as Navigator & { userAgentData?: { getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string; bitness?: string }> } }).userAgentData,
      navigator.userAgent,
    ).then(setAndroidAbi);
  }, []);

  const isAndroid = platform === "android";
  const href = isAndroid ? `/api/download?platform=android&abi=${androidAbi}` : "/api/download";

  return (
    <div className="download-action">
      <div className="download-action-links">
        <a
          id="download"
          href={href}
          className="download-button group"
          aria-label={isAndroid ? `${t("Download for Android")} · XAN` : `${t("Choose installer")} · ${platformLabel(platform)}`}
        >
          <span>{isAndroid ? <T text="Download for Android" /> : <>{t("Choose installer")} · {platformLabel(platform)}</>}</span>
          <svg
            viewBox="0 0 24 24"
            aria-hidden="true"
            className="h-4 w-4 transition-transform duration-300 group-hover:translate-y-0.5"
          >
            <path
              d="M12 3v12m0 0 5-5m-5 5-5-5M5 21h14"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </a>
      </div>

      <p className="download-help">
        {isAndroid
          ? <T text="The latest Android APK will download automatically." />
          : platform === "windows" || platform === "linux" || platform === "macos"
            ? <T text="If an installer for your device is available, it will download automatically." />
            : <T text="Other installers are shown on the downloads page." />}
      </p>
      <a className="download-options-link" href="/download">
        <T text="View all installer options" />
      </a>
    </div>
  );
}
