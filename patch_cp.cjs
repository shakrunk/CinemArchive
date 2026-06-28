const fs = require('fs');
const content = fs.readFileSync('src/components/CommandPalette.tsx', 'utf8');

const newContent = content.replace(
  "import { rankCommands, type Command } from 'src/store/commands'",
  "import { rankCommands, type Command } from 'src/store/commands'\nimport { useAppStore } from 'src/store/useAppStore'\nimport type { AppView } from 'src/lib/navigation'"
).replace(
  "interface CommandPaletteProps {\n  open: boolean\n  onClose: () => void\n  commands: Command[]\n  onRun: (cmd: Command) => void\n}",
  "interface CommandPaletteProps {\n  open: boolean\n  onClose: () => void\n  onNavigate: (view: AppView) => void\n}"
).replace(
  "export function CommandPalette({ open, onClose, commands, onRun }: CommandPaletteProps) {",
  `export function CommandPalette({ open, onClose, onNavigate }: CommandPaletteProps) {
  const titles = useAppStore((s) => s.titles)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const setViewMode = useAppStore((s) => s.setViewMode)

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
    list.push({ id: 'action:layout-grid', kind: 'action', label: 'Library: poster wall', hint: 'layout', keywords: 'grid posters' })
    map['action:layout-grid'] = () => { onNavigate('library'); setViewMode('grid') }
    list.push({ id: 'action:layout-list', kind: 'action', label: 'Library: ledger list', hint: 'layout', keywords: 'list table' })
    map['action:layout-list'] = () => { onNavigate('library'); setViewMode('list') }

    for (const t of titles) {
      const id = \`title:\${t.id}\`
      const hint = [t.director ? \`dir. \${t.director}\` : t.type === 'tv' ? 'series' : 'film', t.year]
        .filter(Boolean)
        .join(' · ')
      list.push({ id, kind: 'title', label: t.title, hint, keywords: t.genres.join(' ') })
      map[id] = () => openDetailDrawer(t.id)
    }
    return { commands: list, runMap: map }
  }, [titles, isSharedView, openAddTitle, openDetailDrawer, setViewMode, onNavigate])

  function handleRun(cmd: Command) {
    onClose()
    runMap[cmd.id]?.()
  }`
).replace(
  "<CommandPaletteBody commands={commands} onRun={onRun} />",
  "<CommandPaletteBody commands={commands} onRun={handleRun} />"
);

fs.writeFileSync('src/components/CommandPalette.tsx', newContent);
