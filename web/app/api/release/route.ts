import { NextResponse } from "next/server";
import { getLatestReleaseCatalog } from "@/lib/github";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    const release = await getLatestReleaseCatalog();

    return NextResponse.json(release, {
      headers: {
        "Cache-Control": "public, s-maxage=300, stale-while-revalidate=3600",
        "Accept-CH": "Sec-CH-UA-Arch, Sec-CH-UA-Bitness",
      },
    });
  } catch (error) {
    console.error("Release lookup failed", error);

    return NextResponse.json(
      { error: "Could not retrieve the latest XAN build." },
      { status: 503 },
    );
  }
}
