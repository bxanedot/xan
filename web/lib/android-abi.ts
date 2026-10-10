export const ANDROID_ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"] as const;

export type AndroidAbi = (typeof ANDROID_ABIS)[number];
export type AndroidDownloadAbi = AndroidAbi | "universal";

export function isAndroidDownloadAbi(value: string | null): value is AndroidDownloadAbi {
  return value === "universal" || ANDROID_ABIS.some((abi) => abi === value);
}

export function detectAndroidAbiFromHints(
  architecture: string | undefined,
  bitness: string | undefined,
  userAgent = "",
): AndroidDownloadAbi {
  const arch = architecture?.toLowerCase().replace(/[\s_-]/g, "") ?? "";
  const bits = bitness?.trim();

  if (["arm64", "aarch64", "arm64v8a"].includes(arch)) return "arm64-v8a";
  if (["armv7", "armv7l", "armeabiv7a"].includes(arch)) return "armeabi-v7a";
  if (["x8664", "amd64"].includes(arch)) return "x86_64";
  if (arch === "x86") return bits === "64" ? "x86_64" : bits === "32" ? "x86" : "universal";
  if (arch === "arm") return bits === "64" ? "arm64-v8a" : bits === "32" ? "armeabi-v7a" : "universal";

  if (/arm64-v8a|aarch64/i.test(userAgent)) return "arm64-v8a";
  if (/armeabi-v7a|armv7l/i.test(userAgent)) return "armeabi-v7a";
  if (/x86_64|amd64/i.test(userAgent)) return "x86_64";
  if (/(?:^|[^a-z])x86(?:[^0-9]|$)/i.test(userAgent)) return "x86";

  // Android browsers often hide CPU details. A universal APK is the safe
  // fallback; guessing a 64-bit ABI can make installation fail on old devices.
  return "universal";
}

type UserAgentDataHints = {
  architecture?: string;
  bitness?: string;
};

type UserAgentDataLike = {
  getHighEntropyValues?: (hints: string[]) => Promise<UserAgentDataHints>;
};

export async function detectAndroidAbiFromBrowser(
  userAgentData?: UserAgentDataLike,
  userAgent = "",
): Promise<AndroidDownloadAbi> {
  if (userAgentData?.getHighEntropyValues) {
    try {
      const hints = await userAgentData.getHighEntropyValues(["architecture", "bitness"]);
      const detected = detectAndroidAbiFromHints(hints.architecture, hints.bitness, userAgent);
      if (detected !== "universal") return detected;
    } catch {
      // Privacy settings can prevent high-entropy hints; continue with UA fallback.
    }
  }
  return detectAndroidAbiFromHints(undefined, undefined, userAgent);
}
