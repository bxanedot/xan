# Motion specification

Motion should preserve the screenshot-led editorial layout: short fades or shared-artwork transitions for screen changes, a readable mini-player expansion, and a restrained spring for Magnet. Avoid adding unrelated motion or continuous idle animation.

The Android baseline already uses Compose transitions, spring-driven Magnet motion, and haptic feedback. Compose animation APIs follow the system animation-duration scale; this pass did not add an app-specific reduce-motion preference or tune timing against the missing screenshots. Accessibility review should confirm that focus and destination selection remain clear when animation is disabled.
