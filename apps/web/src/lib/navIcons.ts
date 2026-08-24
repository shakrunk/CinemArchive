import { LayoutGrid, List, PlayCircle, BarChart3, Compass, ListChecks, type LucideIcon } from 'lucide-react'
import type { NavItemId } from './navigation'
import type { ViewMode } from 'src/store/useAppStore'

export const NAV_ICONS: Record<NavItemId, LucideIcon> = {
  discover: Compass,
  library: LayoutGrid,
  upnext: PlayCircle,
  ledger: BarChart3,
  // Deliberately not the bare `List` glyph — that's already 'library''s
  // list-mode swap-target below, and the two tabs sitting side by side with
  // the same icon would be confusing.
  lists: ListChecks,
}

// 'library' swaps to a list glyph while its poster wall is in list mode.
export function resolveNavIcon(id: NavItemId, viewMode: ViewMode): LucideIcon {
  return id === 'library' && viewMode === 'list' ? List : NAV_ICONS[id]
}
