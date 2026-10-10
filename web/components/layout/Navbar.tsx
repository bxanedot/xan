"use client";

import { useEffect, useState } from "react";
import { writeStoredValue } from "@/lib/safeStorage";
import { Around } from "@/components/ui/around";
import { LanguageSelect, T, useLanguage } from "@/lib/i18n";

const WHITE_ICON = "/xan-white-mark-512.png";
const GOLD_ICON = "/xan-gold-mark-transparent.png";

type Theme = "dark" | "gold";

function setThemeIcons(theme: Theme) {
  const icon = theme === "gold" ? GOLD_ICON : WHITE_ICON;
  document.querySelectorAll<HTMLLinkElement>(
    'link[rel="icon"], link[rel="shortcut icon"], link[rel="apple-touch-icon"]',
  ).forEach((link) => link.setAttribute("href", icon));
}

const mobileItems = [
  {
    label: "Source",
    href: "#source",
    icon: (
      <>
        <path d="m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16" />
      </>
    ),
  },
  {
    label: "App",
    href: "#screenshots",
    icon: (
      <>
        <rect x="6.5" y="3" width="11" height="18" rx="2.5" />
        <path d="M10 6h4" />
      </>
    ),
  },
  {
    label: "EQ",
    href: "#equalizer",
    icon: (
      <>
        <path d="M5 6v12M12 4v16M19 7v10" />
        <circle cx="5" cy="10" r="1.7" />
        <circle cx="12" cy="14" r="1.7" />
        <circle cx="19" cy="11" r="1.7" />
      </>
    ),
  },
  {
    label: "Support",
    href: "#support",
    icon: (
      <>
        <path d="M4 10h13v7a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4z" />
        <path d="M17 12h2a3 3 0 0 1 0 6h-2M7 6c0-1.2 1.2-1.3 1.2-2.5M12 6c0-1.2 1.2-1.3 1.2-2.5" />
      </>
    ),
  },
] as const;

export function Navbar() {
  const { t } = useLanguage();
  const [scrolled, setScrolled] = useState(false);
  const [theme, setTheme] = useState<Theme>("dark");

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 28);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });

    const activeTheme: Theme =
      document.documentElement.dataset.theme === "gold" ? "gold" : "dark";
    setTheme(activeTheme);
    setThemeIcons(activeTheme);

    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  const toggleTheme = () => {
    const nextTheme: Theme = theme === "dark" ? "gold" : "dark";
    setTheme(nextTheme);
    document.documentElement.dataset.theme = nextTheme;
    document.documentElement.style.colorScheme = "dark";
    writeStoredValue("xan-theme", nextTheme);

    setThemeIcons(nextTheme);

    const themeMeta = document.querySelector('meta[name="theme-color"]');
    themeMeta?.setAttribute(
      "content",
      "#080808",
    );
  };

  return (
    <>
      <header className="fixed inset-x-0 top-0 z-50 px-3 pt-3 sm:px-6 sm:pt-4">
        <nav
          className={[
            "site-nav mx-auto flex h-14 max-w-6xl items-center justify-between rounded-full px-3 transition-all duration-500 sm:px-5",
            scrolled
              ? "site-nav-scrolled border border-white/10 bg-black/55 shadow-2xl shadow-black/25 backdrop-blur-2xl"
              : "border border-transparent bg-transparent",
          ].join(" ")}
          aria-label={t("Primary navigation")}
        >
          <a
            href="#top"
            className="group flex shrink-0 items-center gap-2 sm:gap-2.5"
            aria-label="XAN home"
          >
            <span aria-hidden="true" className="xan-brand-mark h-7 w-7 transition-transform duration-300 group-hover:scale-105" />
            <span className="text-xs font-semibold tracking-[0.16em] text-white min-[390px]:text-sm sm:tracking-[0.22em]">
              XAN
            </span>
          </a>

          <div className="hidden items-center gap-2 text-sm text-white/68 sm:flex">
            <a className="nav-link" href="#download"><T text="Download" /></a>
            <a className="nav-link" href="#source"><T text="Source" /></a>
            <a className="nav-link" href="#screenshots"><T text="App" /></a>
            <a className="nav-link" href="#equalizer">EQ</a>
            <a className="nav-link" href="#support"><T text="Support" /></a>
            <LanguageSelect />
            <Around toggled={theme === "gold"} onClick={toggleTheme} />
          </div>

          <div className="flex items-center gap-2 sm:hidden"><LanguageSelect /><Around toggled={theme === "gold"} onClick={toggleTheme} /></div>
        </nav>
      </header>

      <nav className="mobile-dock sm:hidden" aria-label={t("Mobile navigation")}>
        <div className="mobile-dock-inner">
          {mobileItems.map((item) => (
            <a key={item.label} href={item.href} className="mobile-dock-item">
              <svg
                viewBox="0 0 24 24"
                className="h-5 w-5 fill-none stroke-current"
                strokeWidth="1.7"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                {item.icon}
              </svg>
              <span>{t(item.label)}</span>
            </a>
          ))}
          <a
            href="/api/download"
            target="_blank"
            rel="noreferrer"
            className="mobile-dock-download"
            aria-label={`${t("Download")} XAN`}
          >
            <svg
              viewBox="0 0 24 24"
              className="h-5 w-5 fill-none stroke-current"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M12 3v12" />
              <path d="m7.5 10.5 4.5 4.5 4.5-4.5" />
              <path d="M5 20h14" />
            </svg>
          </a>
        </div>
      </nav>
    </>
  );
}
