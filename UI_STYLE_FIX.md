# UI style fix
Cause: styles only changed corner radii (a few dp). The real effects (shadow / glass / gradient / dual shadow)
in UiSurface.kt were never called anywhere, and Home chat rows had no surface at all.
Fix: StyledSurface.kt (new) draws Neumorphism / Glass / Skeuomorphism / Clay.
- 31 Material Surfaces in MainActivity (cards, dialogs, nav pill, top bar) -> StyledSurface
- Home chat rows become styled cards when a style is active (divider hidden); Default style = unchanged
