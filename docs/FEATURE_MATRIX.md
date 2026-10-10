# XAN Fusion feature matrix

Status is for the current XAN checkout after commit `b1be10f`, not a claim that every candidate-app feature has shipped.

| Capability | XAN baseline | Candidate source(s) | Chosen owner / implementation | Migration need | Evidence / test | State |
|---|---|---|---|---|---|---|
| Brand, package identity, updater | Existing XAN identity and `app.xan.music` application ID | All | Existing XAN app and web modules | None in this pass | Source audit; no branding changes | completed for this pass |
| Magnet destinations | Magnet was default; it included recognition instead of Downloads | XAN | Android `NavigationMagnet` and `Screens.Downloads` | None | `NavigationMagnetTest`; debug build | completed on Android |
| Shuffle placement | Player controls already own playback shuffle | XAN | Playback UI only; no Magnet action | None | Destination-list test confirms exact five entries | completed for Magnet contract; player behavior not retested |
| Solid / Liquid Glass selection | Persisted `SolidUiKey`; default already `true`; glass gating reads it | XAN, Convx concepts | Shared Android screens with two-choice appearance selector | Existing key retained | Debug build and unit suite; no runtime visual test | partial |
| Home, Search, Settings visual baseline | Existing Android screens include Listen Now/mood filtering, search, grouped settings | ViviMusic layout ideas; user screenshots are authoritative | Existing XAN Compose screens | None known | Source inspection only; reference images unavailable | partial |
| Unified search / metadata matching | Existing source and search functionality; full normalized cross-provider identity not verified | Meld | Existing XAN source/search modules pending a focused design and test phase | Unknown | Not end-to-end tested in this pass | partial |
| Playback / one Media3 service | Existing Media3 playback integration | XAN, Meld | Existing XAN playback owner | None in this pass | Build only; playback journey not exercised | partial |
| Downloads | Existing ExoPlayer download utility and downloaded-items playlist | XAN | Existing downloaded-items view reused for Magnet | None; no storage changes | Build; route-level behavior not instrumented | partial |
| Lyrics / Listen Together / integrations | Existing provider modules and XAN room backend | Convx, Meld | Existing XAN modules; no migration in this pass | Unknown | Not exercised | partial |
| JioSaavn removal | No provider module/dependency/UI found; one unused stream flag remained | ViviMusic and Convx contain JioSaavn modules; Meld/XAN scan did not find provider modules | XAN Android | No schema/preferences changed | Source/dependency/APK scan recorded in `TEST_REPORT.md` | completed for current XAN source and debug APK |
| 38 design references | No adoption ledger | §11 links | `docs/design/RESOURCE_ADOPTION_MATRIX.md` | None | All 38 numbered entries documented with use or blocker/alternative | partial: accountability complete, broad product adoption not claimed |
| Website / current screenshots | Existing XAN Next.js site and screenshots | Web-oriented reference sites | Existing XAN web project | None | Not redesigned or recaptured | not started |
| Cross-platform parity | Existing iOS/desktop/web structure; repository docs call ports incomplete | All | Existing platform-specific implementations | No changes | No platform builds in this pass | not verified |
