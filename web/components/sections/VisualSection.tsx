"use client";

import { useState } from "react";
import { Reveal } from "@/components/ui/MotionReveal";
import { SpinnableLogo } from "@/components/ui/SpinnableLogo";
import { T } from "@/lib/i18n";

type Appearance = "solid" | "glass";

export function VisualSection() {
  const [appearance, setAppearance] = useState<Appearance>("glass");

  return (
    <section className="section-shell py-28 sm:py-36" aria-labelledby="appearance-title">
      <Reveal>
        <div className="appearance-heading">
          <div>
            <p className="eyebrow"><T text="APPEARANCE" /></p>
            <h2 id="appearance-title" className="mt-4 text-4xl font-medium tracking-[-0.05em] text-white sm:text-6xl">
              <T text="Glass when you want it." />
              <br />
              <T text="Solid when you don’t." />
            </h2>
          </div>
          <div className="appearance-switch" role="group" aria-label="Choose an interface appearance">
            <button
              type="button"
              aria-pressed={appearance === "solid"}
              onClick={() => setAppearance("solid")}
            >
              <T text="Solid UI" />
            </button>
            <button
              type="button"
              aria-pressed={appearance === "glass"}
              onClick={() => setAppearance("glass")}
            >
              <T text="Glass UI" />
            </button>
          </div>
        </div>
      </Reveal>

      <Reveal y={48} amount={0.12}>
        <div className={`visual-stage visual-stage-${appearance}`}>
          <div className="stage-aurora stage-aurora-a" aria-hidden="true" />
          <div className="stage-aurora stage-aurora-b" aria-hidden="true" />
          <div className="stage-grid" aria-hidden="true" />

          <div className="visual-demo-window" aria-live="polite">
            <div className="visual-demo-topline">
              <span className="mode-label">{appearance === "glass" ? <T text="LIQUID GLASS" /> : <T text="SOLID UI" />}</span>
              <SpinnableLogo className="h-7 w-7 rounded-lg object-cover" />
            </div>

            <div className="visual-demo-track">
              <div className="visual-demo-art" aria-hidden="true"><span>X</span></div>
              <div className="visual-demo-track-copy">
                <p>Midnight City</p>
                <span>M83 · Hurry Up, We&apos;re Dreaming</span>
                <div className="visual-demo-progress" aria-hidden="true"><span /></div>
                <div className="visual-demo-time"><span>2:14</span><span>4:03</span></div>
              </div>
            </div>

            <div className="visual-demo-footer">
              <div>
                <p className="visual-demo-caption">{appearance === "glass" ? <T text="Soft layers. Clear focus." /> : <T text="Quiet surfaces. Strong contrast." />}</p>
                <p className="visual-demo-hint"><T text="Switch the appearance to preview both styles." /></p>
              </div>
              <span className="visual-demo-play" aria-label="Playback preview" role="img">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 10 6-10 6V6Z" /></svg>
              </span>
            </div>
          </div>

          <p className="visual-stage-note" aria-live="polite">
            <T text="Previewing" /> {appearance === "glass" ? <T text="Glass UI" /> : <T text="Solid UI" />}
          </p>
        </div>
      </Reveal>
    </section>
  );
}
