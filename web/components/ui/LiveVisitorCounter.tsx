"use client";

import { useEffect, useRef, useState } from "react";

const POLL_INTERVAL_MS = 1_000;
const HEARTBEAT_INTERVAL_MS = 12_000;

type Presence = {
  status: "loading" | "live" | "unavailable";
  count: number | null;
  asOf: string | null;
};

type PresenceResponse = {
  count?: number | null;
  asOf?: string;
};

function formatTime(asOf: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(new Date(asOf));
}

export function LiveVisitorCounter() {
  const elementRef = useRef<HTMLDivElement>(null);
  const [pageVisible, setPageVisible] = useState(false);
  const [inViewport, setInViewport] = useState(false);
  const [presence, setPresence] = useState<Presence>({
    status: "loading",
    count: null,
    asOf: null,
  });

  useEffect(() => {
    const updateVisibility = () => {
      setPageVisible(document.visibilityState === "visible");
    };
    const hide = () => setPageVisible(false);

    updateVisibility();
    document.addEventListener("visibilitychange", updateVisibility);
    window.addEventListener("pageshow", updateVisibility);
    window.addEventListener("pagehide", hide);

    return () => {
      document.removeEventListener("visibilitychange", updateVisibility);
      window.removeEventListener("pageshow", updateVisibility);
      window.removeEventListener("pagehide", hide);
    };
  }, []);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;

    if (typeof IntersectionObserver === "undefined") {
      setInViewport(true);
      return;
    }

    const observer = new IntersectionObserver(([entry]) => {
      setInViewport(entry.isIntersecting);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // Register an active visitor from any visible part of the page. The count
  // itself is polled only when its footer element can be seen.
  useEffect(() => {
    if (!pageVisible) return;

    let controller: AbortController | null = null;
    const heartbeat = async () => {
      if (controller) return;
      const current = new AbortController();
      controller = current;

      try {
        await fetch("/api/presence", {
          method: "POST",
          cache: "no-store",
          credentials: "same-origin",
          signal: current.signal,
        });
      } catch {
        // The visible counter reports backend availability when it next polls.
      } finally {
        if (controller === current) controller = null;
      }
    };

    void heartbeat();
    const timer = window.setInterval(() => void heartbeat(), HEARTBEAT_INTERVAL_MS);
    return () => {
      window.clearInterval(timer);
      controller?.abort();
    };
  }, [pageVisible]);

  useEffect(() => {
    if (!pageVisible || !inViewport) return;

    let controller: AbortController | null = null;
    let stopped = false;
    const poll = async () => {
      if (controller) return;
      const current = new AbortController();
      controller = current;

      try {
        const response = await fetch("/api/presence", {
          cache: "no-store",
          credentials: "same-origin",
          signal: current.signal,
        });
        const data = (await response.json()) as PresenceResponse;
        const valid =
          response.ok &&
          Number.isSafeInteger(data.count) &&
          (data.count ?? -1) >= 0 &&
          typeof data.asOf === "string" &&
          Number.isFinite(Date.parse(data.asOf));

        if (!stopped) {
          if (valid) {
            setPresence({
              status: "live",
              count: data.count as number,
              asOf: data.asOf as string,
            });
          } else {
            setPresence((previous) => ({ ...previous, status: "unavailable" }));
          }
        }
      } catch {
        if (!stopped && !current.signal.aborted) {
          setPresence((previous) => ({ ...previous, status: "unavailable" }));
        }
      } finally {
        if (controller === current) controller = null;
      }
    };

    void poll();
    const timer = window.setInterval(() => void poll(), POLL_INTERVAL_MS);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      controller?.abort();
    };
  }, [pageVisible, inViewport]);

  return (
    <div
      className="visitor-live"
      ref={elementRef}
      title="Browsers active on this site in the last 45 seconds."
    >
      <span
        className={`visitor-live-dot${presence.status === "live" ? "" : " visitor-live-dot-inactive"}`}
        aria-hidden="true"
      />
      {presence.status === "live" ? (
        <>
          <span className="visitor-live-number">{presence.count?.toLocaleString()}</span>
          <span>online now</span>
        </>
      ) : (
        <span role="status">
          {presence.status === "loading"
            ? "Checking online visitors…"
            : "Online count unavailable"}
        </span>
      )}
      {presence.asOf && (
        <time className="visitor-live-updated" dateTime={presence.asOf}>
          Last updated {formatTime(presence.asOf)}
        </time>
      )}
    </div>
  );
}
