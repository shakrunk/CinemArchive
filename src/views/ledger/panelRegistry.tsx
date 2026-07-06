// Maps panel ids to their components and width presets to grid classes.

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

export interface PanelProps {
  className?: string
  settings?: LedgerWidgetSettings
}

export const PANEL_REGISTRY: Record<LedgerPanelId, { Component: (props: PanelProps) => React.ReactElement | null }> = {
  activity: { Component: ActivityHeatmap },
  encores: { Component: EncorePerformances },
  run: { Component: TheRun },
  ratings: { Component: RatingDistribution },
  genres: { Component: GenreBars },
  decades: { Component: DecadeFilmstrip },
  auteurs: { Component: TheAuteurs },
  ensemble: { Component: TheEnsemble },
  runtimes: { Component: RuntimeSpectrum },
  networks: { Component: OnTheAir },
  verdicts: { Component: SecondOpinions },
  languages: { Component: InTranslation },
  weekdays: { Component: ScreeningNights },
  streaks: { Component: TheMarathon },
  trajectory: { Component: ShiftingStandards },
  revivals: { Component: PremieresRevivals },
  timewarp: { Component: TheRevivalHouse },
  progress: { Component: StillRolling },
  attractions: { Component: ComingAttractions },
}

// Grid column span per width preset — panels are always full-width below `lg`.
export const WIDTH_GRID_CLASSES: Record<LedgerPanelWidth, string> = {
  sm: 'col-span-12 lg:col-span-4',
  md: 'col-span-12 lg:col-span-6',
  lg: 'col-span-12 lg:col-span-8',
  full: 'col-span-12',
}
