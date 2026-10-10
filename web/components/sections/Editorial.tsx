import { Reveal } from "@/components/ui/MotionReveal";
import { T } from "@/lib/i18n";

const titleClass =
  "editorial-mobile-title text-[clamp(3rem,17vw,7rem)] font-[640] leading-[0.82] tracking-[-0.075em] sm:text-[clamp(4.4rem,15vw,10rem)]";

export function Editorial() {
  return (
    <section className="section-shell py-28 sm:py-40">
      <Reveal y={42}>
        <div className="editorial-card relative overflow-hidden rounded-[2rem] border border-white/[0.07] bg-[#080808] px-6 py-20 sm:px-10 sm:py-28 lg:px-16 lg:py-36">
          <div className="editorial-glow" aria-hidden="true" />
          <div className="relative z-10 min-w-0">
            <Reveal delay={0.05} y={14}>
              <p className="eyebrow">XAN</p>
            </Reveal>
            <div className="mt-10 min-w-0">
              <Reveal delay={0.08} y={54}>
                <div className={titleClass}><T text="MADE" /></div>
              </Reveal>
              <Reveal delay={0.14} y={54}>
                <div className={titleClass}><T text="FOR" /></div>
              </Reveal>
              <Reveal delay={0.2} y={54}>
                <div className={titleClass}><T text="LISTENING." /></div>
              </Reveal>
            </div>
            <Reveal delay={0.26} y={20}>
              <p className="mt-10 max-w-md text-sm leading-6 text-white/38 sm:ml-auto sm:text-right">
                <T text="No subscription layer between you and your library. Just a modern player built to keep the music in focus." />
              </p>
            </Reveal>
          </div>
        </div>
      </Reveal>
    </section>
  );
}
