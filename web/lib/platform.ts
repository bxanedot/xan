export const INSTALLER_PLATFORMS = ["android", "ios", "macos", "windows", "linux"] as const;

export type InstallerPlatform = (typeof INSTALLER_PLATFORMS)[number];
export type DetectedPlatform = InstallerPlatform | "other";

export function detectPlatform(userAgent: string, platform = "", touchPoints = 0): DetectedPlatform {
  if (/android/i.test(userAgent)) return "android";
  if (/iPhone|iPad|iPod/i.test(userAgent) || (platform === "MacIntel" && touchPoints > 1)) return "ios";
  if (/Macintosh|Mac OS X/i.test(userAgent) || /Mac/i.test(platform)) return "macos";
  if (/Windows/i.test(userAgent) || /Win/i.test(platform)) return "windows";
  if (/Linux/i.test(userAgent) || /Linux/i.test(platform)) return "linux";
  return "other";
}

export function isInstallerPlatform(value: string | null): value is InstallerPlatform {
  return INSTALLER_PLATFORMS.some((platform) => platform === value);
}

export function platformLabel(platform: DetectedPlatform | InstallerPlatform) {
  switch (platform) {
    case "android": return "Android";
    case "ios": return "iOS";
    case "macos": return "macOS";
    case "windows": return "Windows";
    case "linux": return "Linux";
    default: return "your device";
  }
}
