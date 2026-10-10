# Liquid Glass rendering

Liquid Glass is opt-in. Existing Android code uses XAN's Compose backdrop and glass helpers and checks `SolidUiKey`, low-RAM state, and platform support before enabling effects. Solid mode remains the fallback and must not allocate glass rendering work.

This pass did not copy React glass code from the reference sites, alter shader passes, or claim measured refraction quality. The screenshot references make Glass secondary to the Solid layout. Low-end hardware, battery saver, reduced transparency, protected surfaces, GPU cost, and screenshot parity still need device validation.
