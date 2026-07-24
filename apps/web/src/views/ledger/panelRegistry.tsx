// Maps panel ids to their components and width presets to grid classes.
// Every panel is memoized: they subscribe to the store internally and their
// props (className, settings) are referentially stable, so board-level state
// changes (drag hover, selection) don't re-render 19 charts.

import { memo } from 'react'
import type { LedgerPanelId, LedgerPanelWidth, LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { ActivityHeatmap } from './panels/ActivityHeatmap'
import { EncorePerformances } from './panels/EncorePerformances'
import { TheRun } from './panels/TheRun'
import { RatingDistribution } from './panels/RatingDistribution'
import { GenreBars } from './panels/GenreBars'
import { DecadeFilmstrip } from './panels/DecadeFilmstrip'
import { TheAuteurs } from './panels/TheAuteurs'
import { TheEnsemble } from './panels/TheEnsemble'
import { RuntimeSpectrum } from './panels/RuntimeSpectrum'
import { OnTheAir } from './panels/OnTheAir'
import { SecondOpinions } from './panels/SecondOpinions'
import { InTranslation } from './panels/InTranslation'
import { ScreeningNights } from './panels/ScreeningNights'
import { TheMarathon } from './panels/TheMarathon'
import { ShiftingStandards } from './panels/ShiftingStandards'
import { PremieresRevivals } from './panels/PremieresRevivals'
import { TheRevivalHouse } from './panels/TheRevivalHouse'
import { StillRolling } from './panels/StillRolling'
import { ComingAttractions } from './panels/ComingAttractions'
import { AtTheMovies } from './panels/AtTheMovies'

export interface PanelProps {
  className?: string
  settings?: LedgerWidgetSettings
  /** The widget's current board width preset. Most panels ignore this and
   *  rely on flex-wrap to reflow, but a few adapt their internal layout
   *  (donut/list orientation, calendar density, stat-block direction) to it
   *  since it's known up front from the board layout — no ResizeObserver
   *  needed. Absent in isolated previews (e.g. the layout editor's palette),
   *  where panels should fall back to a reasonable default. */
  width?: LedgerPanelWidth
}

export const PANEL_REGISTRY: Record<LedgerPanelId, { Component: React.ComponentType<PanelProps> }> = {
  activity: { Component: memo(ActivityHeatmap) },
  encores: { Component: memo(EncorePerformances) },
  run: { Component: memo(TheRun) },
  ratings: { Component: memo(RatingDistribution) },
  genres: { Component: memo(GenreBars) },
  decades: { Component: memo(DecadeFilmstrip) },
  auteurs: { Component: memo(TheAuteurs) },
  ensemble: { Component: memo(TheEnsemble) },
  runtimes: { Component: memo(RuntimeSpectrum) },
  networks: { Component: memo(OnTheAir) },
  verdicts: { Component: memo(SecondOpinions) },
  languages: { Component: memo(InTranslation) },
  weekdays: { Component: memo(ScreeningNights) },
  streaks: { Component: memo(TheMarathon) },
  trajectory: { Component: memo(ShiftingStandards) },
  revivals: { Component: memo(PremieresRevivals) },
  timewarp: { Component: memo(TheRevivalHouse) },
  progress: { Component: memo(StillRolling) },
  attractions: { Component: memo(ComingAttractions) },
  moviegoing: { Component: memo(AtTheMovies) },
}

// Grid column span per width preset — panels are always full-width below `lg`.
export const WIDTH_GRID_CLASSES: Record<LedgerPanelWidth, string> = {
  sm: 'col-span-12 lg:col-span-4',
  md: 'col-span-12 lg:col-span-6',
  lg: 'col-span-12 lg:col-span-8',
  full: 'col-span-12',
}
