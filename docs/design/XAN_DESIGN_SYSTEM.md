# XAN design system — screenshot-led Solid baseline

The user's three screenshots are the canonical default Solid UI references. Preserved originals are in [`reference/`](reference/README.md); all three are portrait 709 × 1536 captures. The supplied screenshots establish layout and color, while exact dp measurements remain device-density dependent.

## Material modes

- **Solid** is the fresh-install default. Use opaque Material surfaces and avoid starting backdrop capture, blur, or refraction passes.
- **Liquid Glass** is optional and shares the same route tree and feature screens. Glass belongs mainly on floating/transient chrome; artwork, mood tiles, and settings groups remain stable.
- Color theme and motion preferences remain separate from material choice.

## Starting tokens from the screenshot description

These are implementation targets inferred from the screenshots, not pixel-to-dp measurements.

| Token | Initial guidance |
|---|---|
| Page / group / tile / floating surfaces | Near-black `#101010`–`#141414`; group around `#1D1D1F`; tile around `#2E2E30`; floating around `#1D1D20` |
| Primary / secondary text | Off-white near `#F3F3F5`; muted grey only after contrast check |
| Horizontal page gutter | 20–24 dp |
| Cell spacing | 12–16 dp |
| Major section spacing | 24–32 dp, with larger Home editorial gaps |
| Tile / panel / floating corner | 16–20 / 22–30 / 24–30 dp |
| Semantic touch target | At least 48 dp |
| Motion | Purposeful, subdued, and reduced-motion aware |

## Screen structure to preserve

- **Home:** `xan` mark, oversized “Listen Now”, mood chips, editorial horizontal artwork rails with title and artist, and a separate mini-player/Magnet overlay.
- **Search:** oversized Search heading, Explore/Suggestions segmented control, two-column “Moods and moments” tiles, Genres, and an IME-safe search composer.
- **Settings:** back control, oversized title, outlined search field, GENERAL / ACCOUNT / LISTENING / ACTIVITY groups, grouped dark panels, and a safe Magnet overlay.

Do not substitute demo artwork for catalog content. Keep stable list keys and reserve scroll padding for persistent bottom overlays.

## Current code state

The Android project already has a Listen Now screen and mood-filter strings, grouped Settings UI, shared Magnet, Material theme, glass/backdrop helpers, and Solid gating. The Search segmented tabs now expose their selected state to accessibility services and render selected labels with the screen's contrast foreground instead of the potentially dark accent. App backdrop recording now uses a shared Solid/global-glass capability guard. `docs/design/RESOURCE_ADOPTION_MATRIX.md` records where current native equivalents apply. Device screenshot comparison, tablet/foldable behavior, dynamic-type comparison, measured contrast, and frame profiling remain unverified.
