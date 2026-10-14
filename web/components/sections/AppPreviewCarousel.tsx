"use client";

import { useEffect, useState } from "react";

const shots = [
  "home-shortcuts.jpg",
  "library.jpg",
  "recognize-music.jpg",
  "player-audio-settings.jpg",
  "settings.jpg",
  "system-update.jpg",
  "appearance.jpg",
  "about.jpg",
  "home-mini-player.jpg",
  "now-playing.jpg",
  "artist-adele.jpg",
  "listen-together.jpg",
  "integrations.jpg",
  "account-settings.jpg",
  "content-settings.jpg",
  "artist-olivia-rodrigo.jpg",
  "search.jpg",
];
const src = (name: string) => "/screenshots/" + name;

export function AppPreviewCarousel() {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    if (paused || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const timer = window.setInterval(() => setIndex(i => (i + 1) % shots.length), 4500);
    return () => window.clearInterval(timer);
  }, [paused]);
  const move = (direction: number) => setIndex(i => (i + direction + shots.length) % shots.length);
  return (
    <div className="xan-app-preview" aria-label="XAN Android app screenshots" onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onFocus={() => setPaused(true)} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setPaused(false); }}>
      <div className="xan-app-preview-header"><span className="xan-app-preview-dot" /><span>THE XAN EXPERIENCE</span><span>ANDROID</span></div>
      <div className="xan-app-phone">
        <img src={src(shots[index])} alt={`XAN Android app screenshot ${index + 1} of ${shots.length}`} />
      </div>
      <div className="xan-app-preview-controls">
        <button type="button" onClick={() => move(-1)} aria-label="Previous app screenshot">←</button>
        <span aria-live="polite">{index + 1} / {shots.length}</span>
        <button type="button" onClick={() => move(1)} aria-label="Next app screenshot">→</button>
      </div>
      <div className="xan-app-preview-thumbnails" aria-label="Choose an app screenshot">
        {shots.map((shot, i) => <button type="button" key={shot} className={i === index ? "is-active" : ""} onClick={() => setIndex(i)} aria-label={`Show screenshot ${i + 1}`} aria-current={i === index ? "true" : undefined}><img src={src(shot)} alt="" loading="lazy" /></button>)}
      </div>
    </div>
  );
}
