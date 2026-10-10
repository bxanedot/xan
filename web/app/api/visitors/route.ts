import { NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const COUNTER_KEY = "xanapkweb.vercel.app/xan-unique-visitors";
const COOKIE_NAME = "xan_visitor_v1";
const ONE_YEAR = 60 * 60 * 24 * 365;

const HIT_URL =
  `https://hits.sh/${COUNTER_KEY}.svg?label=visitors&color=111111&labelColor=111111`;
const STATS_URL = `https://hits.sh/api/urns/${COUNTER_KEY}`;

function parseSvgCount(svg: string) {
  const matches = [...svg.matchAll(/<text[^>]*>([\d,.]+)<\/text>/gi)];

  for (let index = matches.length - 1; index >= 0; index -= 1) {
    const value = Number(matches[index][1].replace(/[^\d]/g, ""));
    if (Number.isFinite(value)) return value;
  }

  return null;
}

function hasVisitorCookie(request: Request) {
  const cookie = request.headers.get("cookie") ?? "";
  return cookie
    .split(";")
    .map((part) => part.trim())
    .some((part) => part === `${COOKIE_NAME}=1`);
}

async function readCount() {
  const response = await fetch(STATS_URL, {
    cache: "no-store",
    headers: {
      Accept: "application/json",
      "User-Agent": "XAN-Visitor-Counter/2.0",
    },
  });

  if (!response.ok) return null;
  const data = (await response.json()) as { total?: unknown };
  return typeof data.total === "number" && Number.isFinite(data.total)
    ? data.total
    : null;
}

async function increment() {
  const response = await fetch(HIT_URL, {
    cache: "no-store",
    headers: { "User-Agent": "XAN-Visitor-Counter/2.0" },
  });

  if (!response.ok) {
    throw new Error(`Counter increment failed: ${response.status}`);
  }

  return parseSvgCount(await response.text());
}

function responseWithCount(count: number | null, setCookie = false) {
  const response = NextResponse.json(
    { count },
    { headers: { "Cache-Control": "no-store" } },
  );

  if (setCookie) {
    response.cookies.set({
      name: COOKIE_NAME,
      value: "1",
      maxAge: ONE_YEAR,
      httpOnly: true,
      sameSite: "lax",
      secure: true,
      path: "/",
    });
  }

  return response;
}

export async function GET() {
  try {
    return responseWithCount(await readCount());
  } catch (error) {
    console.error("Visitor counter read failed", error);
    return responseWithCount(null);
  }
}

export async function POST(request: Request) {
  try {
    // Server-side guard: a refresh or duplicate client request from the same
    // browser will only read the count, never increment it again.
    if (hasVisitorCookie(request)) {
      return responseWithCount(await readCount());
    }

    const incrementedCount = await increment();
    const storedCount = await readCount().catch(() => null);

    return responseWithCount(storedCount ?? incrementedCount, true);
  } catch (error) {
    console.error("Visitor counter increment failed", error);
    return responseWithCount(null);
  }
}
