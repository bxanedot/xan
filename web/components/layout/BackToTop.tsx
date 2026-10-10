"use client";

import { useEffect, useState } from "react";
import { useLanguage } from "@/lib/i18n";

export function BackToTop() {
  const { t } = useLanguage();
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const update = () => setVisible(window.scrollY > 480);
    update();
    window.addEventListener("scroll", update, { passive: true });
    return () => window.removeEventListener("scroll", update);
  }, []);

  return (
    <button
      className={`back-to-top${visible ? " back-to-top-visible" : ""}`}
      type="button"
      aria-label={t("Back to top")}
      aria-hidden={!visible}
      tabIndex={visible ? 0 : -1}
      onClick={() => window.scrollTo({ top: 0, behavior: "smooth" })}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="m5 14 7-7 7 7M12 7v12" />
      </svg>
    </button>
  );
}
