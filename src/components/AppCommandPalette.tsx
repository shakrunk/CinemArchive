import { useMemo } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import { CommandPalette } from 'src/components/CommandPalette'
import type { AppView } from 'src/lib/navigation'
import type { Command } from 'src/store/commands'

interface AppCommandPaletteProps {
  onNavigate: (view: AppView) => void
}

export function AppCommandPalette({ onNavigate }: AppCommandPaletteProps) {
  // ⚡ Bolt: Isolate title subscription to prevent root App re-renders
  const {
    titles,
    isSharedView,
    user,
    isCommandPaletteOpen,
    closeCommandPalette,
    openAddTitle,
    openDetailDrawer,
    setViewMode
  } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      isSharedView: s.isSharedView,
      user: s.user,
      isCommandPaletteOpen: s.isCommandPaletteOpen,
      closeCommandPalette: s.closeCommandPalette,
      openAddTitle: s.openAddTitle,
      openDetailDrawer: s.openDetailDrawer,
      setViewMode: s.setViewMode,
    }))
  )

  // Build the command list + an id→handler map. Title commands open the drawer
  // (which, via useNavigationSync, becomes a back-button-closable history entry).
  const { commands, runMap } = useMemo(() => {
    const list: Command[] = []
    const map: Record<string, () => void> = {}

    if (!isSharedView) {
      list.push({ id: 'action:add', kind: 'action', label: 'Add a title', hint: 'new', keywords: 'create new movie series' })
      map['action:add'] = () => openAddTitle()
    }
    list.push({ id: 'action:view-upnext', kind: 'action', label: 'Go to Up Next', hint: 'view', keywords: 'continue watching' })
    map['action:view-upnext'] = () => onNavigate('upnext')
    list.push({ id: 'action:view-library', kind: 'action', label: 'Go to the Library', hint: 'view', keywords: 'collection posters' })
    map['action:view-library'] = () => onNavigate('library')
    list.push({ id: 'action:view-ledger', kind: 'action', label: 'Go to the Ledger', hint: 'view', keywords: 'stats dashboard' })
    map['action:view-ledger'] = () => onNavigate('ledger')
    list.push({ id: 'action:view-discover', kind: 'action', label: 'Go to Discover', hint: 'view', keywords: 'explore browse trending genres movies tv' })
    map['action:view-discover'] = () => onNavigate('discover')
    list.push({ id: 'action:view-profile', kind: 'action', label: 'Go to Profile & Settings', hint: 'view', keywords: 'account settings preferences theme shared links sign in out passkey export import' })
    map['action:view-profile'] = () => onNavigate('profile')
    if (user && !isSharedView) {
      list.push({ id: 'action:view-friends', kind: 'action', label: 'Go to Friends', hint: 'view', keywords: 'friends social recommendations activity inbox' })
      map['action:view-friends'] = () => onNavigate('friends')
    }
    list.push({ id: 'action:layout-grid', kind: 'action', label: 'Library: poster wall', hint: 'layout', keywords: 'grid posters' })
    map['action:layout-grid'] = () => { onNavigate('library'); setViewMode('grid') }
    list.push({ id: 'action:layout-list', kind: 'action', label: 'Library: ledger list', hint: 'layout', keywords: 'list table' })
    map['action:layout-list'] = () => { onNavigate('library'); setViewMode('list') }

    for (const t of titles) {
      const id = `title:${t.id}`
      const hint = [t.director ? `dir. ${t.director}` : t.type === 'tv' ? 'series' : 'film', t.year]
        .filter(Boolean)
        .join(' · ')
      list.push({ id, kind: 'title', label: t.title, hint, keywords: t.genres.join(' ') })
      map[id] = () => openDetailDrawer(t.id)
    }
    return { commands: list, runMap: map }
  }, [titles, isSharedView, user, openAddTitle, openDetailDrawer, setViewMode, onNavigate])

  function runCommand(cmd: Command) {
    closeCommandPalette()
    runMap[cmd.id]?.()
  }

  return (
    <CommandPalette
      open={isCommandPaletteOpen}
      onClose={closeCommandPalette}
      commands={commands}
      onRun={runCommand}
    />
  )
}
