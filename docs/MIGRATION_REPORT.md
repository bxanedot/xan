# Migration report

## Changes

- No Room entity, DAO, schema version, DataStore key, download path, account credential, application ID, signing configuration, or deep link changed.
- The existing `SolidUiKey` and its `defaultValue = true` remain in use; no user preference is rewritten.
- Magnet's Downloads destination routes to the existing `auto_playlist/downloaded` screen. No files or download records are moved or deleted.
- Removed only the unused `PlaybackData.isSaavnStream` property. Repository search found no call sites or serialized/database representation.
- `Screens.MainScreens` and `Screens.MainRoutes` are now lazy cached values to avoid an eager static initialization cycle. Their computed sets remain unchanged; Magnet Downloads is not promoted to a persistent tab root.

## Preservation evidence and limits

The Android debug build and unit suite pass. No prior-version database fixture or installed-upgrade test was available in this pass, and no existing installation was modified. User data preservation is therefore structurally low-risk for these source changes, but upgrade compatibility was not end-to-end verified.
