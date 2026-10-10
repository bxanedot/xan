"use client";

import { useEffect, useState } from "react";
import { readStoredValue, removeStoredValue, writeStoredValue } from "@/lib/safeStorage";

const STORAGE_KEY = "xan.unique-visitor.v2";
const LAST_COUNT_KEY = "xan.unique-visitor.last-count.v2";

export function VisitorCounter() {
  const [count, setCount] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;

    const storedCount = readStoredValue(LAST_COUNT_KEY);
    const savedCount = storedCount === null ? NaN : Number(storedCount);
    if (Number.isFinite(savedCount) && savedCount >= 0) {
      setCount(savedCount);
    }

    const update = async () => {
      const alreadyCounted =
        readStoredValue(STORAGE_KEY) === "1";

      // Mark the browser before the network request starts. This prevents a
      // fast refresh / duplicate mount from sending a second increment.
      if (!alreadyCounted) {
        writeStoredValue(STORAGE_KEY, "1");
      }

      try {
        const response = await fetch("/api/visitors", {
          method: alreadyCounted ? "GET" : "POST",
          cache: "no-store",
        });

        const data = (await response.json()) as { count?: number | null };

        if (typeof data.count === "number" && Number.isFinite(data.count)) {
          writeStoredValue(LAST_COUNT_KEY, String(data.count));
          if (!cancelled) setCount(data.count);
        }

        if (!response.ok && !alreadyCounted) {
          removeStoredValue(STORAGE_KEY);
        }
      } catch {
        // Allow one retry on a future page load if the initial increment
        // never reached the server.
        if (!alreadyCounted) {
          removeStoredValue(STORAGE_KEY);
        }
      }
    };

    update();

    const timer = window.setInterval(async () => {
      try {
        const response = await fetch("/api/visitors", {
          method: "GET",
          cache: "no-store",
        });
        const data = (await response.json()) as { count?: number | null };

        if (typeof data.count === "number" && Number.isFinite(data.count)) {
          writeStoredValue(LAST_COUNT_KEY, String(data.count));
          if (!cancelled) setCount(data.count);
        }
      } catch {
        // Keep the most recent known count visible.
      }
    }, 30_000);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  return (
    <div
      className="visitor-total"
      title="Approximate unique browsers recorded since the counter was added."
    >
      <span className="visitor-total-dot" aria-hidden="true" />
      <span className="visitor-total-number">
        {count === null ? "—" : count.toLocaleString()}
      </span>
      <span className="visitor-total-label">unique visitors</span>
    </div>
  );
}
