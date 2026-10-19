import type { Title, WatchStatus } from 'src/store/mockData'

/** An automatic list whose membership is derived from `titles.status` rather than stored
 *  in `lists`/`list_items`. Never persisted or synced — it only exists in the Lists view,
 *  so it never appears in the add-to-list picker, backups or membership sync. Ids use the
 *  reserved `smart:` prefix, which can't collide with a real list's uuid. */
export interface SmartList {
  id: string
  name: string
  description: string
  status: WatchStatus
  emptyMessage: string
}

export const SMART_LISTS: readonly SmartList[] = [
  {
    id: 'smart:watching',
    name: 'Currently Watching',
    description: 'Everything you have in progress.',
    status: 'watching',
    emptyMessage: 'Nothing in progress — mark a title as Watching to see it here.',
  },
  {
    id: 'smart:watchlist',
    name: 'Watchlist',
    description: 'Everything you plan to watch.',
    status: 'watchlist',
    emptyMessage: 'Your watchlist is empty — mark a title as Watchlist to see it here.',
  },
]

export function findSmartList(id: string | null): SmartList | null {
  return SMART_LISTS.find((l) => l.id === id) ?? null
}

export function smartListMembers(titles: readonly Title[], list: SmartList): Title[] {
  return titles.filter((t) => t.status === list.status)
}
