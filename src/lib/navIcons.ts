import { LayoutGrid, List, PlayCircle, BarChart3, Compass, type LucideIcon } from 'lucide-react'
import type { NavItemId } from './navigation'
import type { ViewMode } from 'src/store/useAppStore'

export const NAV_ICONS: Record<NavItemId, LucideIcon> = {
  discover: Compass,
  library: LayoutGrid,
  upnext: PlayCircle,
  ledger: BarChart3,
}

// 'library' swaps to a list glyph while its poster wall is in list mode.
export function resolveNavIcon(id: NavItemId, viewMode: ViewMode): LucideIcon {
  return id === 'library' && viewMode === 'list' ? List : NAV_ICONS[id]
}
