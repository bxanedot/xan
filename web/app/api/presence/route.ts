import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const PRESENCE_KEY = "xan:web:presence:v1";
const COOKIE_NAME = "xan_presence_v1";
const COOKIE_MAX_AGE = 60 * 60 * 24 * 30;
const WINDOW_SECONDS = 45;
const KEY_TTL_SECONDS = 90;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SIGNATURE_PATTERN = /^[0-9a-f]{64}$/;

// A heartbeat, prune, expiry and count execute atomically as one Redis command.
const HEARTBEAT_SCRIPT = `
  redis.call("ZADD", KEYS[1], ARGV[1], ARGV[3])
  redis.call("ZREMRANGEBYSCORE", KEYS[1], "-inf", "(" .. ARGV[2])
  redis.call("EXPIRE", KEYS[1], ARGV[4])
  return redis.call("ZCOUNT", KEYS[1], ARGV[2], "+inf")
`;

type RedisConfig = { url: string; token: string };

function redisConfig(): RedisConfig | null {
  const rawUrl = process.env.UPSTASH_REDIS_REST_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN;
  if (!rawUrl || !token) return null;

  try {
    const url = new URL(rawUrl);
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.pathname !== "/" ||
      url.search ||
      url.hash
    ) {
      return null;
    }
    return { url: url.toString(), token };
  } catch {
    return null;
  }
}

function sign(id: string, token: string): string {
  return createHmac("sha256", token)
    .update(`xan-presence-cookie-v1:${id}`)
    .digest("hex");
}

function visitorId(request: NextRequest, token: string) {
  const value = request.cookies.get(COOKIE_NAME)?.value;
  if (value) {
    const parts = value.split(".");
    const [id, signature] = parts;
    if (
      parts.length === 2 &&
      UUID_PATTERN.test(id) &&
      SIGNATURE_PATTERN.test(signature)
    ) {
      const expected = Buffer.from(sign(id, token), "hex");
      const received = Buffer.from(signature, "hex");
      if (timingSafeEqual(expected, received)) {
        return { id, fresh: false };
      }
    }
  }
  return { id: randomUUID(), fresh: true };
}

async function redisCount(config: RedisConfig, command: (string | number)[]) {
  const response = await fetch(config.url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${config.token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(command),
    cache: "no-store",
    signal: AbortSignal.timeout(5000),
  });

  if (!response.ok) throw new Error(`Redis HTTP ${response.status}`);
  const payload: unknown = await response.json();
  if (
    !payload ||
    typeof payload !== "object" ||
    !("result" in payload) ||
    "error" in payload ||
    typeof payload.result !== "number" ||
    !Number.isSafeInteger(payload.result) ||
    payload.result < 0
  ) {
    throw new Error("Invalid Redis response");
  }
  return payload.result;
}

function unavailable(asOf: string) {
  return NextResponse.json(
    { count: null, asOf, windowSeconds: WINDOW_SECONDS, error: "unavailable" },
    { status: 503, headers: { "Cache-Control": "no-store" } },
  );
}

function available(count: number, asOf: string) {
  return NextResponse.json(
    { count, asOf, windowSeconds: WINDOW_SECONDS },
    { headers: { "Cache-Control": "no-store" } },
  );
}

export async function GET() {
  const now = Date.now();
  const asOf = new Date(now).toISOString();
  const config = redisConfig();
  if (!config) return unavailable(asOf);

  try {
    const activeSince = now - WINDOW_SECONDS * 1000;
    const count = await redisCount(config, [
      "ZCOUNT",
      PRESENCE_KEY,
      activeSince,
      "+inf",
    ]);
    return available(count, asOf);
  } catch (error) {
    console.error("Presence read failed", error);
    return unavailable(asOf);
  }
}

export async function POST(request: NextRequest) {
  const now = Date.now();
  const asOf = new Date(now).toISOString();
  const origin = request.headers.get("origin");
  // Compare against Host because Next dev normalizes localhost aliases in
  // request.url (for example, localhost and 127.0.0.1).
  let originHost: string | null = null;
  if (origin) {
    try {
      originHost = new URL(origin).host;
    } catch {
      return NextResponse.json(
        { error: "forbidden" },
        { status: 403, headers: { "Cache-Control": "no-store" } },
      );
    }
  }
  const requestHost = request.headers.get("host");
  if (originHost && requestHost && originHost !== requestHost) {
    return NextResponse.json(
      { error: "forbidden" },
      { status: 403, headers: { "Cache-Control": "no-store" } },
    );
  }

  const config = redisConfig();
  if (!config) return unavailable(asOf);

  const visitor = visitorId(request, config.token);
  try {
    const activeSince = now - WINDOW_SECONDS * 1000;
    const count = await redisCount(config, [
      "EVAL",
      HEARTBEAT_SCRIPT,
      1,
      PRESENCE_KEY,
      now,
      activeSince,
      visitor.id,
      KEY_TTL_SECONDS,
    ]);
    const response = available(count, asOf);
    if (visitor.fresh) {
      response.cookies.set({
        name: COOKIE_NAME,
        value: `${visitor.id}.${sign(visitor.id, config.token)}`,
        maxAge: COOKIE_MAX_AGE,
        httpOnly: true,
        sameSite: "lax",
        secure: request.nextUrl.protocol === "https:",
        path: "/",
      });
    }
    return response;
  } catch (error) {
    console.error("Presence heartbeat failed", error);
    return unavailable(asOf);
  }
}
