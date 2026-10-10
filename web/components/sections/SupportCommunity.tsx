import { Reveal } from "@/components/ui/MotionReveal";
import { GitHubIcon } from "@/components/ui/GitHubIcon";
import { XAN_LINKS } from "@/lib/links";
import { T } from "@/lib/i18n";

function KoFiIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M5 8h17v12a7 7 0 0 1-7 7h-3a7 7 0 0 1-7-7V8Z" fill="none" stroke="currentColor" strokeWidth="2.5" />
      <path d="M22 11h2.5a4 4 0 0 1 0 8H22" fill="none" stroke="currentColor" strokeWidth="2.5" />
      <path d="M13.5 18.5s-4-2.4-4-5.1a2.2 2.2 0 0 1 4-1.2 2.2 2.2 0 0 1 4 1.2c0 2.7-4 5.1-4 5.1Z" fill="currentColor" />
    </svg>
  );
}

function PatreonIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M5 3h5v26H5z" fill="currentColor" />
      <circle cx="21" cy="11" r="8" fill="currentColor" />
    </svg>
  );
}

function GunsIcon() {
  return <span className="guns-lol-mark" aria-hidden="true" />;
}

function DiscordIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M24.7 8.8a21 21 0 0 0-5.2-1.6l-.7 1.4a19 19 0 0 0-5.6 0l-.7-1.4a21 21 0 0 0-5.2 1.6C4 13.6 3.1 18.3 3.6 22.9a21 21 0 0 0 6.4 3.2l1.4-2.3-2.2-1.1.5-.4c4.2 1.9 8.8 1.9 12.9 0l.5.4-2.2 1.1 1.4 2.3a21 21 0 0 0 6.4-3.2c.6-5.4-1-10-4-14.1ZM11.8 19.5c-1.2 0-2.1-1.1-2.1-2.4s.9-2.4 2.1-2.4 2.1 1.1 2.1 2.4-.9 2.4-2.1 2.4Zm8.4 0c-1.2 0-2.1-1.1-2.1-2.4s.9-2.4 2.1-2.4 2.1 1.1 2.1 2.4-.9 2.4-2.1 2.4Z" fill="currentColor" />
    </svg>
  );
}

function TelegramIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="m27.8 6.1-4.2 20c-.3 1.4-1.1 1.7-2.2 1.1l-6.1-4.5-2.9 2.8c-.3.3-.6.6-1.2.6l.4-6.2L23 9.7c.5-.4-.1-.7-.7-.3L8.1 18.3 2 16.4c-1.3-.4-1.3-1.3.3-1.9L26.2 5.3c1.1-.4 2 .3 1.6.8Z" fill="currentColor" />
    </svg>
  );
}

function BrandLink({ href, label, detail, children }: { href: string; label: string; detail: string; children: React.ReactNode }) {
  return (
    <a className="brand-link" href={href} target="_blank" rel="noreferrer">
      <span className="brand-link-icon">{children}</span>
      <span className="brand-link-text"><strong><T text={label} /></strong><small><T text={detail} /></small></span>
      <svg className="brand-link-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 17 17 7M9 7h8v8" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
    </a>
  );
}

export function SupportCommunity() {
  return (
    <section id="support" className="section-shell support-community-section">
      <div className="support-community-grid">
        <Reveal y={28}>
          <div className="support-panel">
            <p className="eyebrow"><T text="SUPPORT XAN" /></p>
            <h2><T text="Help keep it moving." /></h2>
            <p className="support-description"><T text="Choose the place that works for you." /></p>
            <div className="brand-link-list">
              <BrandLink href={XAN_LINKS.patreon} label="Patreon" detail="Support XAN on Patreon"><PatreonIcon /></BrandLink>
              <BrandLink href={XAN_LINKS.koFi} label="Ko-fi" detail="Buy me a coffee"><KoFiIcon /></BrandLink>
              <BrandLink href={XAN_LINKS.guns} label="guns.lol / bxane" detail="Follow bxane"><GunsIcon /></BrandLink>
              <BrandLink href={XAN_LINKS.githubProfile} label="GitHub profile" detail="Follow bxanedot"><GitHubIcon className="h-7 w-7" /></BrandLink>
            </div>
          </div>
        </Reveal>

        <Reveal delay={0.08} y={28}>
          <div className="support-panel community-panel">
            <p className="eyebrow"><T text="COMMUNITY" /></p>
            <h2><T text="Find your people." /></h2>
            <p className="support-description"><T text="Join XAN updates and conversations." /></p>
            <div className="brand-link-list">
              <BrandLink href={XAN_LINKS.discord} label="Discord" detail="Chat with the community"><DiscordIcon /></BrandLink>
              <BrandLink href={XAN_LINKS.telegram} label="Telegram" detail="Join the Telegram group"><TelegramIcon /></BrandLink>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
