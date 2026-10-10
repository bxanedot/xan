import { ANDROID_ABIS, type AndroidAbi, type AndroidDownloadAbi } from "@/lib/android-abi";

const LATEST_RELEASE_URL = "https://api.github.com/repos/bxanedot/xan/releases/latest";

export const RELEASES_PAGE = "https://github.com/bxanedot/xan/releases/latest";

type GitHubAsset = {
  name?: unknown;
  browser_download_url?: unknown;
  size?: unknown;
};

type GitHubRelease = {
  tag_name?: unknown;
  name?: unknown;
  assets?: unknown;
};

export type InstallerKey = "android" | "windows" | "linux" | "macos";

export type InstallerInfo = {
  version: string;
  releaseName: string;
  downloadUrl: string;
  filename: string;
  size: number | null;
  platform: "Android" | "Windows" | "Linux" | "macOS";
};

export type ReleaseCatalog = {
  version: string;
  releaseName: string;
  installers: Partial<Record<InstallerKey, InstallerInfo>>;
  androidVariants: Partial<Record<AndroidDownloadAbi, InstallerInfo>>;
};

export type ReleaseInfo = InstallerInfo & { platform: "Android" };

function validAsset(asset: GitHubAsset): asset is GitHubAsset & {
  name: string;
  browser_download_url: string;
} {
  return typeof asset.name === "string" &&
    typeof asset.browser_download_url === "string" &&
    !/(source|sources|checksum|checksums|sha256|sha512|\.asc$|\.sig$)/i.test(asset.name);
}

function isApkAsset(asset: GitHubAsset): boolean {
  return validAsset(asset) && asset.name.toLowerCase().endsWith(".apk");
}

function isWindowsAsset(asset: GitHubAsset): boolean {
  if (!validAsset(asset) || !/xan/i.test(asset.name)) return false;
  const name = asset.name.toLowerCase();
  const windowsPackage = /\.(exe|msi|msix|msixbundle|appx|appxbundle)$/.test(name);
  const archive = /\.(zip|7z)$/.test(name) && /(windows|win32|win64|win[-_. ]?(x64|arm64)|installer|setup)/i.test(name);
  return windowsPackage || archive;
}

function isLinuxAsset(asset: GitHubAsset): boolean {
  if (!validAsset(asset) || !/xan/i.test(asset.name)) return false;
  const name = asset.name.toLowerCase();
  const linuxPackage = /\.(appimage|deb|rpm|flatpak)$/.test(name) ||
    /\.pkg\.tar\.(zst|xz|gz)$/.test(name);
  const archive = /\.(tar\.(gz|xz|zst)|zip|7z)$/.test(name) && /(linux|appimage)/i.test(name);
  return linuxPackage || archive;
}

function isMacOSAsset(asset: GitHubAsset): boolean {
  if (!validAsset(asset) || !/xan/i.test(asset.name)) return false;
  const name = asset.name.toLowerCase();
  const macPackage = /\.(dmg|pkg)$/.test(name);
  const archive = /\.(zip|7z|tar\.gz)$/.test(name) && /(macos|mac-os|osx|darwin)/i.test(name);
  return macPackage || archive;
}

function preferenceScore(asset: GitHubAsset, platform: InstallerKey) {
  if (!validAsset(asset)) return 0;
  const name = asset.name.toLowerCase();
  const architecture = /arm64|aarch64/.test(name)
    ? 1
    : /x86_64|x64|amd64/.test(name)
      ? 3
      : 0;
  const format = platform === "linux" && name.endsWith(".appimage")
    ? 2
    : platform === "macos" && name.endsWith(".dmg")
      ? 2
    : platform === "windows" && /\.(exe|msi)$/.test(name)
      ? 2
      : name.endsWith(".deb")
        ? 1
        : 0;
  return architecture + format;
}

function selectAsset(assets: GitHubAsset[], platform: InstallerKey) {
  const matches = assets.filter((asset) => {
    if (platform === "android") return isApkAsset(asset);
    if (platform === "windows") return isWindowsAsset(asset);
    if (platform === "linux") return isLinuxAsset(asset);
    return isMacOSAsset(asset);
  });

  if (platform === "android") {
    return matches.find((asset) => validAsset(asset) && asset.name.toLowerCase() === "xan-universal.apk") ??
      matches.find((asset) => validAsset(asset) && asset.name.toLowerCase() === "xan.apk");
  }

  return matches.sort((left, right) => preferenceScore(right, platform) - preferenceScore(left, platform))[0];
}

function androidAbiAsset(assets: GitHubAsset[], abi: AndroidAbi) {
  const filename = `xan-${abi}.apk`;
  return assets.find((asset) => validAsset(asset) && asset.name.toLowerCase() === filename);
}

function toInstallerInfo(
  asset: GitHubAsset & { name: string; browser_download_url: string },
  version: string,
  releaseName: string,
  platform: InstallerInfo["platform"],
): InstallerInfo {
  return {
    version,
    releaseName,
    downloadUrl: asset.browser_download_url,
    filename: asset.name,
    size: typeof asset.size === "number" ? asset.size : null,
    platform,
  };
}

export async function getLatestReleaseCatalog(): Promise<ReleaseCatalog> {
  const response = await fetch(LATEST_RELEASE_URL, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "xanapkweb",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    next: { revalidate: 60 },
  });

  if (!response.ok) throw new Error(`GitHub release lookup failed: ${response.status}`);

  const release = (await response.json()) as GitHubRelease;
  if (typeof release.tag_name !== "string" || !release.tag_name.trim()) {
    throw new Error("Latest release is missing a version tag.");
  }
  if (!Array.isArray(release.assets)) throw new Error("Latest release has no asset list.");

  const assets = release.assets.filter(
    (asset): asset is GitHubAsset => Boolean(asset) && typeof asset === "object",
  );
  const version = release.tag_name;
  const releaseName = typeof release.name === "string" && release.name.trim()
    ? release.name
    : version;
  const installers: ReleaseCatalog["installers"] = {};
  const androidVariants: ReleaseCatalog["androidVariants"] = {};

  for (const platform of ["android", "windows", "linux", "macos"] as const) {
    const asset = selectAsset(assets, platform);
    if (!asset || !validAsset(asset)) continue;
    const platformName = platform === "android" ? "Android" : platform === "windows" ? "Windows" : platform === "linux" ? "Linux" : "macOS";
    installers[platform] = toInstallerInfo(asset, version, releaseName, platformName);
  }

  const universal = installers.android;
  if (universal) androidVariants.universal = universal;
  for (const abi of ANDROID_ABIS) {
    const asset = androidAbiAsset(assets, abi);
    if (asset && validAsset(asset)) androidVariants[abi] = toInstallerInfo(asset, version, releaseName, "Android");
  }

  return { version, releaseName, installers, androidVariants };
}

export async function getLatestRelease(): Promise<ReleaseInfo> {
  const catalog = await getLatestReleaseCatalog();
  const android = catalog.installers.android;
  if (!android || android.platform !== "Android") {
    throw new Error("Latest release has no installable Android APK.");
  }
  return android as ReleaseInfo;
}
