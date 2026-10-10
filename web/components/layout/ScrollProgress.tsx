"use client";

import { motion, useReducedMotion, useScroll } from "motion/react";

export function ScrollProgress() {
  const { scrollYProgress } = useScroll();
  const reduced = useReducedMotion();

  if (reduced) return null;

  return (
    <motion.div
      aria-hidden="true"
      className="fixed inset-x-0 top-0 z-[80] h-px origin-left bg-white/80 shadow-[0_0_14px_rgba(255,255,255,0.35)]"
      style={{ scaleX: scrollYProgress }}
    />
  );
}
