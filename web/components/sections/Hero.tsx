import { AppPreviewCarousel } from "@/components/sections/AppPreviewCarousel";
import { CloneCommand } from "@/components/ui/CloneCommand";
import { DownloadButton } from "@/components/ui/DownloadButton";
import { GitHubIcon } from "@/components/ui/GitHubIcon";
import { XAN_LINKS } from "@/lib/links";
import { T } from "@/lib/i18n";

export function Hero() {
  return (
    <section id="top" className="hero-shell section-shell xan-app-hero">
      <div className="hero-ambient" aria-hidden="true" />
      <div className="xan-app-hero-grid">
        <div className="xan-app-hero-copy">
          <p className="eyebrow"><T text="OPEN-SOURCE MUSIC PLAYER · ANDROID + DESKTOP" /></p>
          <div className="xan-app-brandline"><span className="xan-brand-mark" aria-hidden="true" /><span>XAN</span></div>
          <h1 className="hero-title"><T text="Your music." /><br /><span className="gradient-text"><T text="Your way." /></span></h1>
          <p className="hero-description"><T text="Keep your library, discover artists, and download available catalogues in the app." /></p>
          <div className="hero-actions">
            <DownloadButton />
            <a className="github-repo-button" href={XAN_LINKS.repository} target="_blank" rel="noreferrer">
              <GitHubIcon className="github-repo-icon" /><T text="View source code on GitHub" />
            </a>
          </div>
          <div className="xan-app-hero-pills" aria-label="XAN features"><span>Library</span><span>Lyrics</span><span>Equalizer</span><span>Playlists</span></div>
        </div>
        <AppPreviewCarousel />
      </div>
      <div id="source" className="hero-source xan-app-source">
        <div className="hero-source-heading"><span className="source-glyph" aria-hidden="true">&gt;_</span><div><p className="eyebrow"><T text="CLONE THE SOURCE" /></p><h2><T text="Start with the code." /></h2></div></div>
        <p className="hero-source-copy"><T text="Choose your system, copy the command, and clone the public repository." /></p>
        <CloneCommand />
        <p className="hero-source-note"><T text="Git is required. XAN is currently available for Android." /></p>
      </div>
      <a className="hero-scroll-link" href="#equalizer"><T text="Explore XAN" /> <span aria-hidden="true">↓</span></a>
    </section>
  );
}
