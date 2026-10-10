import { Reveal } from "@/components/ui/MotionReveal";
import { SpinnableLogo } from "@/components/ui/SpinnableLogo";
import { VisitorCounter } from "@/components/ui/VisitorCounter";
import { LiveVisitorCounter } from "@/components/ui/LiveVisitorCounter";
import { T } from "@/lib/i18n";

export function Footer() {
  return (
    <footer className="section-shell pb-10 pt-24">
      <Reveal y={24}>
        <div className="flex flex-col gap-10 border-t border-white/[0.08] pt-10 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="flex items-center gap-3">
              <SpinnableLogo className="h-8 w-8 rounded-lg object-cover" />
              <span className="text-sm font-semibold tracking-[0.24em] text-white">
                XAN
              </span>
            </div>
            <p className="mt-4 text-sm text-white/34">
              <T text="A modern open-source music player for Android." />
            </p>
            <div className="visitor-stats mt-4">
              <VisitorCounter />
              <LiveVisitorCounter />
            </div>
          </div>

          <div className="sm:text-right">
            <p className="mt-5 text-xs tracking-[0.1em] text-white/22">© 2026 XAN</p>
            <p className="mt-2 text-xs text-white/28"><T text="Made by bxane" /></p>
          </div>
        </div>
      </Reveal>
    </footer>
  );
}
