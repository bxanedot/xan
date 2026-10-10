# Magnet navigation contract

Magnet is the default floating navigation entry. In the current Android implementation it expands to exactly these five destinations, in this order:

1. Home
2. Search
3. Library
4. Downloads
5. Settings

Downloads reuses `auto_playlist/downloaded`. Recognition is outside Magnet and remains available through Search. Shuffle stays in playback UI and is not represented as a Magnet destination or action.

Tapping the button expands/collapses the menu; selecting a destination navigates and closes it; Back and outside dismissal close an open menu. The existing saved back stack and player connection remain owned by MainActivity. `Screens.Downloads` is intentionally not added to persistent tab roots.

Solid and Glass use the same menu content. The existing Glass renderer is gated by `SolidUiKey` and hardware support. The Magnet's semantic description identifies XAN navigation. Unit test `NavigationMagnetTest` asserts the exact destination list and routes; runtime, TalkBack, rotation, and overlay hit-target checks remain open.
