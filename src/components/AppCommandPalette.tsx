import { useMemo } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { CommandPalette } from 'src/components/CommandPalette'
import { useAppStore } from 'src/store/useAppStore'
import type { AppView } from 'src/lib/navigation'
import type { Command } from 'src/store/commands'

interface AppCommandPaletteProps {
  setCurrentView: (view: AppView) => void
}

export function AppCommandPalette({ setCurrentView }: AppCommandPaletteProps) {
  const {
    titles, isSharedView, openAddTitle, openDetailDrawer,
    setViewMode, isCommandPaletteOpen, closeCommandPalette
  } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      isSharedView: s.isSharedView,
      openAddTitle: s.openAddTitle,
      openDetailDrawer: s.openDetailDrawer,
      setViewMode: s.setViewMode,
      isCommandPaletteOpen: s.isCommandPaletteOpen,
      closeCommandPalette: s.closeCommandPalette
    }))
  )

  const { commands, runMap } = useMemo(() => {
    const list: Command[] = []
    const map: Record<string, () => void> = {}

    if (!isSharedView) {
      list.push({ id: 'action:add', kind: 'action', label: 'Add a title', hint: 'new', keywords: 'create new movie series' })
      map['action:add'] = () => openAddTitle()
    }
    list.push({ id: 'action:view-upnext', kind: 'action', label: 'Go to Up Next', hint: 'view', keywords: 'continue watching' })
    map['action:view-upnext'] = () => setCurrentView('upnext')
    list.push({ id: 'action:view-library', kind: 'action', label: 'Go to the Library', hint: 'view', keywords: 'collection posters' })
    map['action:view-library'] = () => setCurrentView('library')
    list.push({ id: 'action:view-ledger', kind: 'action', label: 'Go to the Ledger', hint: 'view', keywords: 'stats dashboard' })
    map['action:view-ledger'] = () => setCurrentView('ledger')
    list.push({ id: 'action:view-discover', kind: 'action', label: 'Go to Discover', hint: 'view', keywords: 'explore browse trending genres movies tv' })
    map['action:view-discover'] = () => setCurrentView('discover')
    list.push({ id: 'action:layout-grid', kind: 'action', label: 'Library: poster wall', hint: 'layout', keywords: 'grid posters' })
    map['action:layout-grid'] = () => { setCurrentView('library'); setViewMode('grid') }
    list.push({ id: 'action:layout-list', kind: 'action', label: 'Library: ledger list', hint: 'layout', keywords: 'list table' })
    map['action:layout-list'] = () => { setCurrentView('library'); setViewMode('list') }

    for (const t of titles) {
      const id = `title:${t.id}`
      const hint = [t.director ? `dir. ${t.director}` : t.type === 'tv' ? 'series' : 'film', t.year]
        .filter(Boolean)
        .join(' · ')
      list.push({ id, kind: 'title', label: t.title, hint, keywords: t.genres.join(' ') })
      map[id] = () => openDetailDrawer(t.id)
    }
    return { commands: list, runMap: map }
  }, [titles, isSharedView, openAddTitle, openDetailDrawer, setViewMode, setCurrentView])

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
