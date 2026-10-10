"use client";
import { useEffect, useRef } from "react";

type SpinnableLogoProps = { className: string; buttonClassName?: string; ariaLabel?: string };
export function SpinnableLogo({ className, buttonClassName = "", ariaLabel = "Boost XAN logo rotation" }: SpinnableLogoProps) {
  const imageRef = useRef<HTMLSpanElement>(null);
  const animationRef = useRef<Animation | null>(null);
  const boostTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const preference = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncMotion = () => {
      if (boostTimer.current !== null) clearTimeout(boostTimer.current);
      animationRef.current?.cancel();
      animationRef.current = preference.matches ? null : imageRef.current?.animate(
        [{ transform: "rotate(0deg)" }, { transform: "rotate(360deg)" }],
        { duration: 4500, iterations: Infinity, easing: "linear" },
      ) ?? null;
    };
    syncMotion();
    preference.addEventListener("change", syncMotion);
    return () => {
      preference.removeEventListener("change", syncMotion);
      animationRef.current?.cancel();
      if (boostTimer.current !== null) clearTimeout(boostTimer.current);
    };
  }, []);

  const boost = () => {
    const animation = animationRef.current;
    if (!animation) return;
    if (boostTimer.current !== null) clearTimeout(boostTimer.current);
    animation.updatePlaybackRate(4);
    boostTimer.current = setTimeout(() => {
      animation.updatePlaybackRate(1);
      boostTimer.current = null;
    }, 1800);
  };

  return <button type="button" aria-label={ariaLabel} className={`spinnable-logo ${buttonClassName}`} onClick={boost}><span ref={imageRef} aria-hidden="true" className={`xan-brand-mark ${className}`} /></button>;
}
