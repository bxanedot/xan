import { Reveal } from "@/components/ui/MotionReveal";
import { T } from "@/lib/i18n";

const features = [
  {
    number: "01",
    title: "Download an artist",
    copy: "Download an artist’s available catalogue in Android.",
  },
  {
    number: "02",
    title: "Local playback",
    copy: "Built around the music already on your device, with a full player and queue controls.",
  },
  {
    number: "03",
    title: "Search & library",
    copy: "Move through home, search and your library without the interface getting in the way.",
  },
  {
    number: "04",
    title: "Lyrics",
    copy: "Keep the words close while the track stays front and center.",
  },
];

export function FeatureSection() {
  return (
    <section id="features" className="section-shell py-28 sm:py-36">
      <Reveal>
        <div className="mb-16 flex flex-col gap-6 border-b border-white/[0.08] pb-10 md:flex-row md:items-end md:justify-between">
          <div>
            <p className="eyebrow"><T text="LISTEN" /></p>
            <h2 className="mt-4 max-w-3xl text-4xl font-medium tracking-[-0.05em] text-white sm:text-6xl">
              <T text="Music should feel simple." />
            </h2>
          </div>
          <p className="max-w-sm text-sm leading-6 text-white/40">
            <T text="XAN keeps the essentials close: playback, search, your library, and lyrics." />
          </p>
        </div>
      </Reveal>

      <div className="features-grid grid gap-px overflow-hidden rounded-[1.75rem] border border-white/[0.08] bg-white/[0.08] md:auto-rows-fr md:grid-cols-2 lg:grid-cols-4">
        {features.map((feature, index) => (
          <Reveal key={feature.number} className="h-full" delay={index * 0.07} y={34} amount={0.15}>
            <article className="feature-card h-full min-h-[190px] bg-[#090909] p-6">
              <span className="text-[10px] font-medium tracking-[0.25em] text-white/24">
                {feature.number}
              </span>
              <div className="mt-6">
                <h3 className="text-lg font-medium tracking-[-0.04em] text-white">
                  <T text={feature.title} />
                </h3>
                <p className="mt-2 max-w-md text-xs leading-5 text-white/40">
                  <T text={feature.copy} />
                </p>
              </div>
            </article>
          </Reveal>
        ))}
      </div>
    </section>
  );
}
