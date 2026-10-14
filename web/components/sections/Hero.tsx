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
        <div className="xan-native-panel" aria-label="XAN-inspired music app interface">
          <div className="xan-native-top"><span className="xan-native-logo">XAN</span><span className="xan-native-avatar">♫</span></div>
          <h2>Listen Now</h2>
          <div className="xan-native-chips"><span className="selected">All</span><span>Music</span><span>Playlists</span><span>Downloaded</span></div>
          <div className="xan-native-feature"><div className="xan-native-art">✦</div><div><small>YOUR SOUND, YOUR SPACE</small><strong>Made for listening.</strong><p>Discover. Save. Play.</p></div></div>
          <div className="xan-native-section-title"><strong>Recently Played</strong><span>See all →</span></div>
          <div className="xan-native-albums"><div><span className="xan-native-cover cover-a">♪</span><strong>Discover</strong><small>Explore music</small></div><div><span className="xan-native-cover cover-b">◈</span><strong>Your Library</strong><small>Saved favorites</small></div><div><span className="xan-native-cover cover-c">♫</span><strong>Mixes</strong><small>For every mood</small></div></div>
          <div className="xan-native-mini"><span className="xan-native-mini-art">♫</span><span><strong>XAN Player</strong><small>Your music, uninterrupted</small></span><span className="xan-native-play">▶</span></div>
          <div className="xan-native-tabs"><span>⌂<small>Listen Now</small></span><span>⌕<small>Search</small></span><span>▤<small>Library</small></span><span>⚙<small>Settings</small></span></div>
        </div>
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
