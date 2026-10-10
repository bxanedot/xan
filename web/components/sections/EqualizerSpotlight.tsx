"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { readStoredValue, writeStoredValue } from "@/lib/safeStorage";
import { T, useLanguage } from "@/lib/i18n";

const FREQUENCIES = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
const PRESETS = {
  Flat: { bass: 0, mids: 0, treble: 0 },
  "XAN Signature": { bass: 2, mids: 0, treble: 4 },
  "Bass Boost": { bass: 7, mids: 1, treble: 0 },
  "Treble Boost": { bass: 0, mids: 1, treble: 7 },
  Warm: { bass: 4, mids: 2, treble: -2 },
} as const;

type PresetName = keyof typeof PRESETS;
type EqMode = "basic" | "advanced";
type BasicGains = { bass: number; mids: number; treble: number };
type PresetState = PresetName | "Custom";

const BAND_KEY = "xan.eq.custom-bands.v1";
const PRESET_KEY = "xan.eq.preset";
const BASIC_KEY = "xan.eq.basic.v1";
const MODE_KEY = "xan.eq.mode.v1";

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function toBands({ bass, mids, treble }: BasicGains) {
  const anchors = [
    [31, bass], [125, bass], [500, mids], [1000, mids], [4000, treble], [16000, treble],
  ] as const;
  return FREQUENCIES.map((frequency) => {
    const upper = anchors.findIndex(([anchor]) => anchor >= frequency);
    if (upper <= 0) return anchors[Math.max(upper, 0)][1];
    const [leftFrequency, leftGain] = anchors[upper - 1];
    const [rightFrequency, rightGain] = anchors[upper];
    const ratio = (Math.log(frequency) - Math.log(leftFrequency)) /
      (Math.log(rightFrequency) - Math.log(leftFrequency));
    return Math.round((leftGain + (rightGain - leftGain) * ratio) * 2) / 2;
  });
}

function toBasic(bands: number[]): BasicGains {
  const average = (values: number[]) => values.reduce((sum, value) => sum + value, 0) / values.length;
  return {
    bass: Math.round(average(bands.slice(0, 3))),
    mids: Math.round(average(bands.slice(3, 7))),
    treble: Math.round(average(bands.slice(7, 10))),
  };
}

function formatGain(value: number) {
  return `${value > 0 ? "+" : ""}${Number.isInteger(value) ? value : value.toFixed(1)}`;
}

function dispatchBands(name: PresetState, gains: number[]) {
  window.dispatchEvent(new CustomEvent("xan:eq-preset", { detail: { name, gains: [...gains] } }));
}

function basicFromStorage(): BasicGains {
  try {
    const raw = readStoredValue(BASIC_KEY);
    if (raw) {
      const value = JSON.parse(raw) as BasicGains;
      if ([value.bass, value.mids, value.treble].every(Number.isFinite)) {
        return { bass: clamp(value.bass, -10, 10), mids: clamp(value.mids, -10, 10), treble: clamp(value.treble, -10, 10) };
      }
    }
  } catch { /* Use the product preset when saved data is malformed. */ }
  return { ...PRESETS["XAN Signature"] };
}

function bandsFromStorage(basic: BasicGains) {
  try {
    const raw = JSON.parse(readStoredValue(BAND_KEY) ?? "null") as unknown;
    if (Array.isArray(raw) && raw.length === FREQUENCIES.length && raw.every(Number.isFinite)) {
      return raw.map((gain) => clamp(Number(gain), -12, 12));
    }
  } catch { /* Use the basic EQ mapping when saved bands are malformed. */ }
  return toBands(basic);
}

function polarPoint(value: number, axis: number, radius: number) {
  const angle = [-Math.PI / 2, Math.PI / 6, (5 * Math.PI) / 6][axis];
  const pointRadius = radius * (0.46 + (value / 10) * 0.34);
  return { x: 160 + Math.cos(angle) * pointRadius, y: 160 + Math.sin(angle) * pointRadius };
}

function wavePath(gains: BasicGains) {
  const values = [gains.mids, gains.bass, gains.treble];
  const angles = [-Math.PI / 2, Math.PI / 6, (5 * Math.PI) / 6];
  const points = Array.from({ length: 97 }, (_, index) => {
    const angle = (index / 96) * Math.PI * 2 - Math.PI / 2;
    let radius = 52;
    values.forEach((value, axis) => {
      let delta = angle - angles[axis];
      delta = Math.atan2(Math.sin(delta), Math.cos(delta));
      const weight = Math.max(0, Math.cos(delta * 0.75));
      radius += (value / 10) * 52 * weight * weight;
    });
    return `${index === 0 ? "M" : "L"}${160 + Math.cos(angle) * radius} ${160 + Math.sin(angle) * radius}`;
  });
  return `${points.join(" ")} Z`;
}

export function EqualizerSpotlight() {
  const { t } = useLanguage();
  const reduced = useReducedMotion();
  const circleRef = useRef<SVGSVGElement>(null);
  const draggingAxis = useRef<number | null>(null);
  const [mode, setMode] = useState<EqMode>("basic");
  const [activePreset, setActivePreset] = useState<PresetState>("XAN Signature");
  const [basic, setBasic] = useState<BasicGains>({ ...PRESETS["XAN Signature"] });
  const [bands, setBands] = useState<number[]>(() => toBands(PRESETS["XAN Signature"]));

  const allBands = useMemo(() => mode === "basic" ? toBands(basic) : bands, [mode, basic, bands]);

  useEffect(() => {
    const savedMode = readStoredValue(MODE_KEY);
    const savedBasic = basicFromStorage();
    const savedBands = bandsFromStorage(savedBasic);
    const savedPreset = readStoredValue(PRESET_KEY) as PresetState | null;
    setBasic(savedBasic);
    setBands(savedBands);
    setMode(savedMode === "advanced" ? "advanced" : "basic");
    if (savedPreset && (savedPreset === "Custom" || savedPreset in PRESETS)) setActivePreset(savedPreset);
    dispatchBands(savedPreset === "Custom" ? "Custom" : "XAN Signature", savedBands);
  }, []);

  const persistBasic = useCallback((next: BasicGains, preset: PresetState) => {
    setBasic(next);
    setActivePreset(preset);
    writeStoredValue(BASIC_KEY, JSON.stringify(next));
    writeStoredValue(PRESET_KEY, preset);
    const nextBands = toBands(next);
    setBands(nextBands);
    writeStoredValue(BAND_KEY, JSON.stringify(nextBands));
    dispatchBands(preset, nextBands);
  }, []);

  const changeBasic = (key: keyof BasicGains, value: number) => {
    persistBasic({ ...basic, [key]: clamp(value, -10, 10) }, "Custom");
  };

  const selectPreset = (name: PresetName) => persistBasic({ ...PRESETS[name] }, name);

  const changeBand = (index: number, value: number) => {
    const next = bands.map((gain, bandIndex) => bandIndex === index ? clamp(value, -12, 12) : gain);
    setBands(next);
    setBasic(toBasic(next));
    setActivePreset("Custom");
    writeStoredValue(PRESET_KEY, "Custom");
    writeStoredValue(BAND_KEY, JSON.stringify(next));
    writeStoredValue(BASIC_KEY, JSON.stringify(toBasic(next)));
    dispatchBands("Custom", next);
  };

  const changeMode = (next: EqMode) => {
    if (next === "advanced" && mode === "basic") {
      const converted = toBands(basic);
      setBands(converted);
      writeStoredValue(BAND_KEY, JSON.stringify(converted));
      dispatchBands(activePreset, converted);
    } else if (next === "basic" && mode === "advanced") {
      const converted = toBasic(bands);
      setBasic(converted);
      writeStoredValue(BASIC_KEY, JSON.stringify(converted));
    }
    setMode(next);
    writeStoredValue(MODE_KEY, next);
  };

  const onPointerDown = (event: React.PointerEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = ((event.clientX - rect.left) / rect.width) * 320;
    const y = ((event.clientY - rect.top) / rect.height) * 320;
    let angle = Math.atan2(y - 160, x - 160);
    const axisAngles = [-Math.PI / 2, Math.PI / 6, (5 * Math.PI) / 6];
    const distance = (target: number) => Math.abs(Math.atan2(Math.sin(angle - target), Math.cos(angle - target)));
    const axis = axisAngles.reduce((best, target, index) => distance(target) < distance(axisAngles[best]) ? index : best, 0);
    if (Math.hypot(x - 160, y - 160) < 44 || Math.hypot(x - 160, y - 160) > 151) return;
    draggingAxis.current = axis;
    event.currentTarget.setPointerCapture(event.pointerId);
    const radius = Math.hypot(x - 160, y - 160);
    const normalized = clamp((radius / 104 - 0.46) / 0.34, -1, 1);
    changeBasic(((["mids", "bass", "treble"] as const)[axis]), Math.round(normalized * 10));
  };

  const onPointerMove = (event: React.PointerEvent<SVGSVGElement>) => {
    if (draggingAxis.current === null) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const x = ((event.clientX - rect.left) / rect.width) * 320;
    const y = ((event.clientY - rect.top) / rect.height) * 320;
    const radius = Math.hypot(x - 160, y - 160);
    const normalized = clamp((radius / 104 - 0.46) / 0.34, -1, 1);
    changeBasic(((["mids", "bass", "treble"] as const)[draggingAxis.current]), Math.round(normalized * 10));
  };

  const valueControls: { key: keyof BasicGains; label: string; axis: number }[] = [
    { key: "mids", label: "Mids", axis: 0 },
    { key: "treble", label: "Treble", axis: 2 },
    { key: "bass", label: "Bass", axis: 1 },
  ];

  return (
    <section id="equalizer" className="relative mx-auto max-w-7xl px-5 py-24 sm:px-8 lg:px-10">
      <div className="grid items-center gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-16">
        <div className="max-w-xl">
          <p className="eq-kicker text-xs font-medium tracking-[0.28em]"><T text="XAN EQUALIZER" /></p>
          <h2 className="eq-title mt-3 text-4xl font-medium tracking-[-0.05em] sm:text-5xl"><T text="Tune it your way." /></h2>
          <p className="eq-copy mt-5 text-base leading-7">
            <T text="Start with XAN’s circular three-range tuner, then open the five-band view for finer control. Your curve stays on this device and follows you between modes." />
          </p>
          <div className="mt-7 flex flex-wrap gap-2" aria-label={t("Equalizer presets")}>
            {(Object.keys(PRESETS) as PresetName[]).map((preset) => (
              <button key={preset} type="button" onClick={() => selectPreset(preset)} aria-pressed={activePreset === preset} className={`eq-preset ${activePreset === preset ? "eq-preset-active" : ""}`}>
                {preset}
              </button>
            ))}
          </div>
          <div className="mt-7 grid gap-3 sm:grid-cols-2">
            <div className="eq-info-card rounded-2xl p-4">
              <p className="eq-info-title text-sm font-medium"><T text="Simple controls" /></p>
              <p className="eq-info-copy mt-1 text-sm leading-6"><T text="Drag a range around the circle, or focus its slider and use the arrow keys." /></p>
            </div>
            <div className="eq-info-card rounded-2xl p-4">
              <p className="eq-info-title text-sm font-medium"><T text="XAN frequencies" /></p>
              <p className="eq-info-copy mt-1 text-sm leading-6"><T text="Advanced bands are sampled from the app’s real 31 Hz–16 kHz equalizer." /></p>
            </div>
          </div>
        </div>

        <motion.div initial={reduced ? false : { opacity: 0, y: 24 }} whileInView={reduced ? undefined : { opacity: 1, y: 0 }} viewport={{ once: true, amount: 0.25 }} transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1] }} className="relative">
          <div className="eq-glow absolute inset-10 rounded-full blur-3xl" aria-hidden="true" />
          <div className="eq-panel relative overflow-hidden rounded-[2rem] p-5 sm:p-7">
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="eq-panel-kicker text-[10px] font-medium tracking-[0.24em]">XAN EQ</p>
                <p className="eq-panel-title mt-1 text-lg font-medium tracking-[-0.03em]">{activePreset}</p>
              </div>
              <div className="eq-mode-switch" role="group" aria-label={t("Equalizer mode")}>
                {(["basic", "advanced"] as const).map((value) => (
                  <button key={value} type="button" aria-pressed={mode === value} onClick={() => changeMode(value)}>{value === "basic" ? <T text="Basic" /> : <T text="Advanced" />}</button>
                ))}
              </div>
            </div>

            <div className="eq-interactive-stage mt-5">
              <AnimatePresence mode="wait" initial={false}>
                {mode === "basic" ? (
                  <motion.div key="basic" initial={reduced ? false : { opacity: 0, scale: 0.96 }} animate={{ opacity: 1, scale: 1 }} exit={reduced ? undefined : { opacity: 0, scale: 0.96 }} transition={{ duration: 0.22 }} className="eq-basic-wrap">
                    <svg ref={circleRef} viewBox="0 0 320 320" className="eq-circle" role="group" aria-label="Circular three-range equalizer. Drag toward a range to change its gain." onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={() => { draggingAxis.current = null; }} onPointerCancel={() => { draggingAxis.current = null; }}>
                      <circle className="eq-circle-surface" cx="160" cy="160" r="145" />
                      <circle className="eq-circle-guide" cx="160" cy="160" r="112" />
                      <circle className="eq-circle-guide" cx="160" cy="160" r="76" />
                      <path className="eq-axis" d="M160 160V27 M160 160l115 66 M160 160 45 226" />
                      <path className="eq-wave-fill" d={wavePath(basic)} />
                      <path className="eq-wave-line" d={wavePath(basic)} />
                      <text className="eq-center-wordmark" x="160" y="156" textAnchor="middle">XAN</text>
                      <text className="eq-center-caption" x="160" y="178" textAnchor="middle">SIGNATURE EQ</text>
                      {valueControls.map(({ key, axis }) => {
                        const point = polarPoint(basic[key], axis, 112);
                        return <circle key={key} className="eq-control-dot" cx={point.x} cy={point.y} r="9" />;
                      })}
                      <text className="eq-axis-label" x="160" y="20" textAnchor="middle">MIDS</text>
                      <text className="eq-axis-label" x="34" y="247" textAnchor="start">TREBLE</text>
                      <text className="eq-axis-label" x="286" y="247" textAnchor="end">BASS</text>
                    </svg>
                    <div className="eq-basic-values" aria-label={t("Basic equalizer gain controls")}>
                      {valueControls.map(({ key, label }) => (
                        <label className="eq-basic-control" key={key}>
                          <span>{label}</span><output>{formatGain(basic[key])} dB</output>
                          <input type="range" min="-10" max="10" step="1" value={basic[key]} onChange={(event) => changeBasic(key, Number(event.target.value))} aria-label={`${label} gain`} aria-valuetext={`${formatGain(basic[key])} dB`} />
                        </label>
                      ))}
                    </div>
                  </motion.div>
                ) : (
                  <motion.div key="advanced" initial={reduced ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={reduced ? undefined : { opacity: 0, y: 10 }} transition={{ duration: 0.22 }}>
                    <div className="eq-bands" aria-label={t("Ten-band equalizer")}>
                      {FREQUENCIES.map((frequency) => {
                        const index = FREQUENCIES.indexOf(frequency);
                        const gain = bands[index];
                        const label = frequency >= 1000 ? `${frequency / 1000} kHz` : `${frequency} Hz`;
                        return (
                          <label className="eq-band" key={frequency}>
                            <output>{formatGain(gain)} dB</output>
                            <div className="eq-band-track">
                              <span className="eq-zero-line" />
                              <span className="eq-band-fill" style={{ height: `${((gain + 12) / 24) * 100}%` }} />
                              <input type="range" min="-12" max="12" step="0.5" value={gain} onChange={(event) => changeBand(index, Number(event.target.value))} aria-label={`${label} gain`} aria-valuetext={`${formatGain(gain)} dB`} />
                            </div>
                            <span className="eq-band-label">{label}</span>
                          </label>
                        );
                      })}
                    </div>
                    <p className="eq-advanced-note"><T text="All ten adjustable bands from 31 Hz to 16 kHz." /></p>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>

            <div className="eq-footer mt-6 flex items-center justify-between gap-4 pt-5">
              <div><p className="eq-footer-label text-xs"><T text="Curve" /></p><p className="eq-footer-value mt-1 text-sm font-medium">{activePreset}</p></div>
              <div className="flex items-center gap-2 text-right"><span className="eq-live-dot h-2 w-2 rounded-full" /><span className="eq-live-text text-xs">{mode === "basic" ? "Three ranges · −10 to +10 dB" : "Ten bands · −12 to +12 dB"}</span></div>
            </div>
            <span className="sr-only" aria-live="polite">{mode === "basic" ? `Treble ${formatGain(basic.treble)}, Mids ${formatGain(basic.mids)}, Bass ${formatGain(basic.bass)} decibels` : `Advanced EQ ${allBands.map(formatGain).join(", ")} decibels`}</span>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
