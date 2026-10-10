import { NextResponse } from "next/server";
import { getLatestReleaseCatalog, RELEASES_PAGE, type InstallerKey } from "@/lib/github";
import { detectPlatform, isInstallerPlatform } from "@/lib/platform";
import { detectAndroidAbiFromHints, isAndroidDownloadAbi } from "@/lib/android-abi";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const url = new URL(request.url);
  const requestedPlatform = url.searchParams.get("platform");
  const detectedPlatform = detectPlatform(request.headers.get("user-agent") ?? "");
  const platform = isInstallerPlatform(requestedPlatform)
    ? requestedPlatform
    : detectedPlatform;

  if (platform !== "android" && platform !== "windows" && platform !== "linux" && platform !== "macos") {
    return NextResponse.redirect(new URL(`/download?platform=${platform}`, request.url), 307);
  }

  try {
    const release = await getLatestReleaseCatalog();
    const requestedAbi = url.searchParams.get("abi");
    const detectedAbi = detectAndroidAbiFromHints(
      request.headers.get("sec-ch-ua-arch") ?? undefined,
      request.headers.get("sec-ch-ua-bitness") ?? undefined,
      request.headers.get("user-agent") ?? "",
    );
    const androidAbi = isAndroidDownloadAbi(requestedAbi) ? requestedAbi : detectedAbi;
    const installer = platform === "android"
      ? release.androidVariants[androidAbi] ?? release.androidVariants.universal ?? release.installers.android
      : release.installers[platform as InstallerKey];
    if (!installer) {
      return NextResponse.redirect(
        new URL(`/download?platform=${platform}&unavailable=1`, request.url),
        307,
      );
    }
    return NextResponse.redirect(installer.downloadUrl, {
      status: 307,
      headers: {
        "Accept-CH": "Sec-CH-UA-Arch, Sec-CH-UA-Bitness",
        "Vary": "Sec-CH-UA-Arch, Sec-CH-UA-Bitness, User-Agent",
        "Cache-Control": "no-store",
      },
    });
  } catch (error) {
    console.error("Download resolution failed", error);
    return NextResponse.redirect(new URL(RELEASES_PAGE, request.url), 307);
  }
}
