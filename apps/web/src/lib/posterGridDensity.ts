import type { GridSize } from 'src/store/useAppStore'

/**
 * Layout-only half of the poster grid's density presets — the numbers that
 * both index.css's `.poster-wall` rules and the virtualized grid's column/row
 * math (`virtual-poster-wall.tsx`) need to agree on, so there's exactly one
 * place to change a density's size. `minPx` is the narrowest an auto-fill
 * column may get (`>=640px` viewports, mirroring `.poster-wall`'s
 * `grid-template-columns: repeat(auto-fill, minmax(minPx, 1fr))`); `cols` is
 * the explicit column count below that breakpoint (`.poster-wall`'s
 * `repeat(var(--poster-cols), 1fr)` under `@media (max-width: 640px)`).
 *
 * Library.tsx's `GRID_SIZES` re-exposes these same numbers alongside the
 * label/icon each density shows in the UI.
 */
export const POSTER_GRID_DENSITY: Record<GridSize, { minPx: number; cols: number }> = {
  compact: { minPx: 130, cols: 3 },
  default: { minPx: 180, cols: 2 },
  large: { minPx: 260, cols: 1 },
}
