# Appearance modes

Android Settings → Appearance now exposes **UI Style** with exactly two choices: **Solid** and **Liquid Glass**. The existing `SolidUiKey` remains the persisted DataStore preference and defaults to `true`, so a new installation stays Solid without a migration.

Material choice is independent of the existing color and motion settings. Existing `MainActivity` and `GlassEffect` code gates glass when Solid is selected; the same screens, routes, queue, and feature state are reused. Selecting Liquid Glass reveals its existing glass tuning page.

Build and unit tests pass. Live playback continuity, persistence across process death, accessibility modes, and screenshots in Light/Dark/AMOLED were not exercised in this pass.
