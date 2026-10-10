"use client";

import { useEffect, useRef, useState } from "react";
import { T, useLanguage } from "@/lib/i18n";

const screenshots = [
  {
    src: "/screenshots/magnet-expanded.jpg",
    label: "Magnet navigation",
    alt: "XAN Search screen with the Magnet shortcut menu expanded",
  },
  {
    src: "/screenshots/08.jpg",
    label: "Legacy navigation",
    alt: "XAN Listen Now screen with the classic bottom navigation bar",
  },
  {
    src: "/screenshots/01.jpg",
    label: "Media controls",
    alt: "XAN lock screen media controls",
  },
  {
    src: "/screenshots/02.jpg",
    label: "Top songs",
    alt: "XAN Search suggestions showing top songs",
  },
  {
    src: "/screenshots/03.jpg",
    label: "Trending albums",
    alt: "XAN Search suggestions showing trending albums",
  },
  {
    src: "/screenshots/04.jpg",
    label: "Explore",
    alt: "XAN Explore screen with moods and moments",
  },
  {
    src: "/screenshots/05.jpg",
    label: "Search",
    alt: "XAN Search screen with the keyboard open",
  },
  {
    src: "/screenshots/06.jpg",
    label: "Mini player",
    alt: "XAN Search screen with the floating mini player",
  },
  {
    src: "/screenshots/07.jpg",
    label: "Library",
    alt: "XAN Library screen",
  },
  {
    src: "/screenshots/09.jpg",
    label: "Recently played",
    alt: "XAN home screen showing recently played music",
  },
  {
    src: "/screenshots/10.jpg",
    label: "Appearance",
    alt: "XAN Appearance settings screen",
  },
  {
    src: "/screenshots/11.jpg",
    label: "Equalizer",
    alt: "XAN advanced equalizer controls",
  },
  {
    src: "/screenshots/12.jpg",
    label: "Settings",
    alt: "XAN settings screen",
  },
  {
    src: "/screenshots/13.jpg",
    label: "Search",
    alt: "XAN Search suggestions and trending albums",
  },
  {
    src: "/screenshots/14.jpg",
    label: "Recognize Music",
    alt: "XAN music recognition screen",
  },
  {
    src: "/screenshots/15.jpg",
    label: "Library",
    alt: "XAN Library with playlists, downloaded music, and local files",
  },
  {
    src: "/screenshots/16.jpg",
    label: "Now playing",
    alt: "XAN full-screen player with track details and playback controls",
  },
] as const;

export function ScreenshotShowcase() {
  const { t } = useLanguage();
  const sectionRef = useRef<HTMLElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ startX: number; startScrollLeft: number; moved: boolean } | null>(null);
  const suppressClickRef = useRef(false);
  const hoveredRef = useRef(false);
  const focusedRef = useRef(false);
  const visibleRef = useRef(false);
  const [active, setActive] = useState(0);
  const [expanded, setExpanded] = useState<number | null>(null);

  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;

    const updateActive = () => {
      const center = track.scrollLeft + track.clientWidth / 2;
      let closest = 0;
      let closestDistance = Number.POSITIVE_INFINITY;

      Array.from(track.children).forEach((child, index) => {
        const element = child as HTMLElement;
        const childCenter = element.offsetLeft + element.offsetWidth / 2;
        const distance = Math.abs(childCenter - center);

        if (distance < closestDistance) {
          closestDistance = distance;
          closest = index;
        }
      });

      setActive(closest);
    };

    updateActive();
    track.addEventListener("scroll", updateActive, { passive: true });
    window.addEventListener("resize", updateActive);

    return () => {
      track.removeEventListener("scroll", updateActive);
      window.removeEventListener("resize", updateActive);
    };
  }, []);

  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;

    if (typeof IntersectionObserver === "undefined") {
      visibleRef.current = true;
      return;
    }

    const observer = new IntersectionObserver(([entry]) => {
      visibleRef.current = entry.isIntersecting;
    }, { threshold: 0.12 });
    observer.observe(section);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const handlePointerMove = (event: PointerEvent) => {
      const drag = dragRef.current;
      const track = trackRef.current;
      if (!drag || !track) return;

      const distance = event.clientX - drag.startX;
      if (!drag.moved && Math.abs(distance) < 5) return;
      drag.moved = true;
      track.classList.add("is-dragging");
      track.scrollLeft = drag.startScrollLeft - distance;
    };

    const handlePointerUp = () => {
      const drag = dragRef.current;
      if (!drag) return;

      dragRef.current = null;
      trackRef.current?.classList.remove("is-dragging");
      if (drag.moved) {
        suppressClickRef.current = true;
        window.setTimeout(() => { suppressClickRef.current = false; }, 0);
      }
    };

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
    window.addEventListener("pointercancel", handlePointerUp);
    return () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", handlePointerUp);
      window.removeEventListener("pointercancel", handlePointerUp);
    };
  }, []);

  useEffect(() => {
    if (
      expanded !== null ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ) return;

    const interval = window.setInterval(() => {
      if (
        !visibleRef.current ||
        hoveredRef.current ||
        focusedRef.current ||
        dragRef.current ||
        document.visibilityState !== "visible"
      ) return;

      const track = trackRef.current;
      const next = (active + 1) % screenshots.length;
      const item = track?.children[next] as HTMLElement | undefined;
      item?.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "center" });
    }, 4800);

    return () => window.clearInterval(interval);
  }, [active, expanded]);

  useEffect(() => {
    if (expanded === null) return;

    const previousFocus = document.activeElement as HTMLElement | null;
    const siblings = Array.from(dialogRef.current?.parentElement?.children ?? []).filter(node => node !== dialogRef.current) as HTMLElement[];
    const previousInert = siblings.map(node => node.inert);
    siblings.forEach(node => { node.inert = true; });
    const previousOverflow = document.body.style.overflow;
    dialogRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    document.body.style.overflow = "hidden";

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Tab") {
        const controls = Array.from(dialogRef.current?.querySelectorAll<HTMLButtonElement>("button") ?? []);
        const first = controls[0];
        const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
      if (event.key === "Escape") {
        setExpanded(null);
        return;
      }

      if (event.key === "ArrowLeft") {
        setExpanded((current) =>
          current === null
            ? current
            : (current - 1 + screenshots.length) % screenshots.length,
        );
      }

      if (event.key === "ArrowRight") {
        setExpanded((current) =>
          current === null ? current : (current + 1) % screenshots.length,
        );
      }
    };

    window.addEventListener("keydown", onKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      siblings.forEach((node, index) => { node.inert = previousInert[index]; });
      previousFocus?.focus();
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [expanded !== null]);

  const scrollTo = (index: number) => {
    const track = trackRef.current;
    const item = track?.children[index] as HTMLElement | undefined;

    item?.scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
      block: "nearest",
      inline: "center",
    });
  };

  const move = (direction: number) => {
    const next = Math.min(
      screenshots.length - 1,
      Math.max(0, active + direction),
    );
    scrollTo(next);
  };

  const moveExpanded = (direction: number) => {
    setExpanded((current) => {
      if (current === null) return current;
      return (current + direction + screenshots.length) % screenshots.length;
    });
  };

  return (
    <>
      <section
        ref={sectionRef}
        id="screenshots"
        className="relative mx-auto max-w-7xl px-5 py-24 sm:px-8 lg:px-10"
      >
        <div className="mb-9 flex items-end justify-between gap-6">
          <div>
            <p className="text-xs font-medium tracking-[0.28em] text-white/35">
              <T text="THE APP" />
            </p>
            <h2 className="mt-3 text-4xl font-medium tracking-[-0.05em] text-white sm:text-5xl">
              <T text="Inside XAN." />
            </h2>
            <p className="mt-3 max-w-xl text-sm leading-6 text-white/45 sm:text-base">
              <T text="Screenshots move automatically. Drag or swipe to browse; select one to expand it." />
            </p>
          </div>

          <div className="hidden gap-2 sm:flex">
            <button
              type="button"
              onClick={() => move(-1)}
              disabled={active === 0}
              className="grid h-11 w-11 place-items-center rounded-full border border-white/10 bg-white/[0.04] text-xl text-white transition hover:bg-white/[0.09] disabled:cursor-not-allowed disabled:opacity-25"
              aria-label={t("Previous screenshot")}
            >
              ←
            </button>
            <button
              type="button"
              onClick={() => move(1)}
              disabled={active === screenshots.length - 1}
              className="grid h-11 w-11 place-items-center rounded-full border border-white/10 bg-white/[0.04] text-xl text-white transition hover:bg-white/[0.09] disabled:cursor-not-allowed disabled:opacity-25"
              aria-label={t("Next screenshot")}
            >
              →
            </button>
          </div>
        </div>

        <div
          ref={trackRef}
          className="gallery-track -mx-5 flex snap-x snap-mandatory gap-4 overflow-x-auto scroll-smooth px-5 pb-5 sm:-mx-8 sm:gap-5 sm:px-8 lg:-mx-10 lg:px-10"
          style={{ scrollbarWidth: "none" }}
          tabIndex={0}
          onPointerDown={(event) => {
            if (event.pointerType !== "mouse" || event.button !== 0) return;
            const track = trackRef.current;
            if (!track) return;
            suppressClickRef.current = false;
            dragRef.current = {
              startX: event.clientX,
              startScrollLeft: track.scrollLeft,
              moved: false,
            };
          }}
          onMouseEnter={() => { hoveredRef.current = true; }}
          onMouseLeave={() => { hoveredRef.current = false; }}
          onFocusCapture={() => { focusedRef.current = true; }}
          onBlurCapture={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
              focusedRef.current = false;
            }
          }}
          onKeyDown={(event) => {
            if (event.key === "ArrowLeft") move(-1);
            if (event.key === "ArrowRight") move(1);
          }}
          aria-label={t("XAN app screenshots")}
        >
          {screenshots.map((shot, index) => (
            <button
              key={shot.src}
              type="button"
              onClick={(event) => {
                if (suppressClickRef.current) {
                  event.preventDefault();
                  return;
                }
                setActive(index);
                setExpanded(index);
              }}
              className="group w-[58vw] max-w-[250px] shrink-0 snap-center cursor-zoom-in text-left sm:w-[250px]"
              aria-label={`${t("Expand screenshot")}: ${t(shot.label)}`}
            >
              <div
                className={
                  "galaxy-s26-frame relative transition duration-500 " +
                  (active === index
                    ? "scale-100 border-white/20 opacity-100 shadow-black/60"
                    : "scale-[0.96] border-white/[0.07] opacity-55 shadow-black/30 group-hover:opacity-85")
                }
              >
                <div className="galaxy-s26-screen">
                <img
                  src={shot.src}
                  alt={shot.alt}
                  width={709}
                  height={1536}
                  loading={index < 2 ? "eager" : "lazy"}
                  draggable={false}
                  className="block h-auto w-full select-none"
                />
                <span className="galaxy-s26-camera" aria-hidden="true" />
                </div>
                <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/20 via-transparent to-white/[0.03]" />
                <div className="pointer-events-none absolute right-3 top-3 grid h-9 w-9 place-items-center rounded-full border border-white/15 bg-black/55 text-base text-white/85 opacity-0 backdrop-blur-md transition group-hover:opacity-100">
                  ↗
                </div>
              </div>
              <div className="mt-3 flex items-center justify-between px-1">
                <span className="text-sm font-medium text-white/70">
                  {t(shot.label)}
                </span>
                <span className="text-[10px] tracking-[0.18em] text-white/25">
                  {String(index + 1).padStart(2, "0")}
                </span>
              </div>
            </button>
          ))}
        </div>

        <p className="gallery-position" role="status" aria-live="polite">{active + 1} / {screenshots.length} · {t(screenshots[active].label)}</p>
        <div className="gallery-dots mt-2 flex items-center justify-center gap-2 sm:hidden">
          {screenshots.map((shot, index) => (
            <button
              key={shot.src}
              type="button"
              onClick={() => scrollTo(index)}
              className={
                "h-1.5 rounded-full transition-all " +
                (active === index ? "w-7 bg-white/80" : "w-1.5 bg-white/20")
              }
              aria-current={active === index ? "true" : undefined}
              aria-label={`Go to screenshot ${index + 1}`}
            />
          ))}
        </div>
      </section>

      {expanded !== null && (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/90 p-3 backdrop-blur-xl sm:p-6"
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-label={`Expanded ${screenshots[expanded].label} screenshot`}
          onClick={() => setExpanded(null)}
        >
          <button
            type="button"
            onClick={() => setExpanded(null)}
            className="absolute right-4 top-4 z-20 grid h-11 w-11 place-items-center rounded-full border border-white/15 bg-black/60 text-2xl text-white backdrop-blur-md transition hover:bg-white/10 sm:right-6 sm:top-6"
            aria-label={t("Close expanded screenshot")}
          >
            ×
          </button>

          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              moveExpanded(-1);
            }}
            className="absolute left-3 top-1/2 z-20 grid h-12 w-12 -translate-y-1/2 place-items-center rounded-full border border-white/15 bg-black/60 text-2xl text-white backdrop-blur-md transition hover:bg-white/10 sm:left-6"
            aria-label={t("Previous screenshot")}
          >
            ←
          </button>

          <div
            className="flex max-h-[94vh] max-w-[92vw] flex-col items-center gap-3"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="galaxy-s26-frame galaxy-s26-expanded">
            <div className="galaxy-s26-screen">
            <img
              src={screenshots[expanded].src}
              alt={screenshots[expanded].alt}
              width={709}
              height={1536}
              className="block h-auto w-full select-none"
            />
            <span className="galaxy-s26-camera" aria-hidden="true" />
            </div>
            </div>
            <div className="text-center">
              <p className="text-sm font-medium text-white/90">
                {t(screenshots[expanded].label)}
              </p>
              <p className="mt-1 text-xs tracking-[0.15em] text-white/35">
                {String(expanded + 1).padStart(2, "0")} /{" "}
                {String(screenshots.length).padStart(2, "0")}
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              moveExpanded(1);
            }}
            className="absolute right-3 top-1/2 z-20 grid h-12 w-12 -translate-y-1/2 place-items-center rounded-full border border-white/15 bg-black/60 text-2xl text-white backdrop-blur-md transition hover:bg-white/10 sm:right-6"
            aria-label={t("Next screenshot")}
          >
            →
          </button>
        </div>
      )}
    </>
  );
}
