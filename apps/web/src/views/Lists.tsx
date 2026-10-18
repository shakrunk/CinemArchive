import { saveSucceeded } from 'src/lib/localSave'
import { useEffect, useState, useMemo } from 'react'
import { ListChecks, Plus, Trash2, ArrowLeft, X, Sparkles } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { EmptyState } from 'src/components/ui/empty-state'
import { PosterThumb } from 'src/components/ui/poster-thumb'
import { SMART_LISTS, findSmartList, smartListMembers, type SmartList } from 'src/lib/smartLists'
import type { List, Title } from 'src/store/mockData'

// ─── New list creation ──────────────────────────────────────────────────────

function NewListForm() {
  const createList = useAppStore((s) => s.createList)
  const [name, setName] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    if (!await saveSucceeded(createList(trimmed))) return
    setName('')
  }

  return (
    <form onSubmit={handleSubmit} className="flex gap-2 max-w-sm">
      <Input
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="New list name…"
        aria-label="New list name"
      />
      <Button
        type="submit"
        disabled={!name.trim()}
        className="shrink-0 bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium"
      >
        <Plus className="w-4 h-4 mr-1" />
        Create
      </Button>
    </form>
  )
}

// ─── List grid ──────────────────────────────────────────────────────────────

function ListCard({ list, onOpen }: { list: List; onOpen: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const memberIds = useAppStore((s) => s.listMemberships[list.id])
  // ⚡ Bolt: Memoize expensive O(N) array filtering to prevent unnecessary calculations on every render
  const memberTitles = useMemo(() => titles.filter((t) => memberIds?.has(t.id)), [titles, memberIds])

  return <ListCardShell name={list.name} description={list.description} memberTitles={memberTitles} onOpen={onOpen} />
}

function SmartListCard({ list, onOpen }: { list: SmartList; onOpen: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const memberTitles = useMemo(() => smartListMembers(titles, list), [titles, list])

  return <ListCardShell name={list.name} description={list.description} memberTitles={memberTitles} onOpen={onOpen} automatic />
}

function ListCardShell({ name, description, memberTitles, onOpen, automatic = false }: {
  name: string
  description: string | null
  memberTitles: Title[]
  onOpen: () => void
  automatic?: boolean
}) {
  return (
    <button
      onClick={onOpen}
      className="text-left rounded-xl border p-4 sm:p-5 transition-colors hover:border-amber/40 focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
      style={{ borderColor: 'var(--line)', background: 'var(--wash)' }}
    >
      <div className="flex items-center gap-2">
        <p className="font-serif text-lg leading-snug truncate" style={{ color: 'var(--paper)' }}>
          {name}
        </p>
        {automatic && (
          <span className="flex items-center gap-1 shrink-0 font-mono text-[10px] uppercase tracking-wider text-amber">
            <Sparkles className="w-3 h-3" aria-hidden="true" />
            Auto
          </span>
        )}
      </div>
      {description && (
        <p className="font-sans text-xs text-muted-foreground mt-1 line-clamp-2">{description}</p>
      )}
      <div className="flex items-center justify-between mt-4">
        <div className="flex -space-x-3">
          {memberTitles.slice(0, 4).map((t) => (
            <div key={t.id} className="ring-2 ring-(--wash) rounded overflow-hidden">
              <PosterThumb src={t.posterUrl} alt={t.title} type={t.type} size="sm" />
            </div>
          ))}
        </div>
        <span className="font-mono text-xs text-muted-foreground shrink-0">
          {memberTitles.length} {memberTitles.length === 1 ? 'title' : 'titles'}
        </span>
      </div>
    </button>
  )
}

function ListsEmptyState() {
  const createList = useAppStore((s) => s.createList)

  return (
    <EmptyState
      Icon={ListChecks}
      title="No custom lists yet."
      subtext="Group titles into custom lists — a marathon, a ranked shortlist, anything you like."
      subtextClassName="mb-6"
      ctaLabel="Create your first list"
      onCta={() => createList('My List')}
    />
  )
}

const LIST_GRID_CLASS = 'grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4'

function SmartListsGrid({ onOpen }: { onOpen: (id: string) => void }) {
  return (
    <div className={LIST_GRID_CLASS}>
      {SMART_LISTS.map((list) => (
        <SmartListCard key={list.id} list={list} onOpen={() => onOpen(list.id)} />
      ))}
    </div>
  )
}

function ListsGrid({ onOpen }: { onOpen: (id: string) => void }) {
  const lists = useAppStore((s) => s.lists)

  if (lists.length === 0) return <ListsEmptyState />

  return (
    <div className={LIST_GRID_CLASS}>
      {lists.map((list) => (
        <ListCard key={list.id} list={list} onOpen={() => onOpen(list.id)} />
      ))}
    </div>
  )
}

// ─── List detail ────────────────────────────────────────────────────────────

const POSTER_GRID_CLASS = 'grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-4'

function BackToLists({ onBack }: { onBack: () => void }) {
  return (
    <button
      onClick={onBack}
      className="flex items-center gap-1.5 font-mono text-xs text-muted-foreground hover:text-amber transition-colors mb-4 focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded"
    >
      <ArrowLeft className="w-3.5 h-3.5" />
      All lists
    </button>
  )
}

function PosterLink({ title, onOpen }: { title: Title; onOpen: () => void }) {
  return (
    <button onClick={onOpen} className="block w-full text-left focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60 rounded-lg">
      <img
        src={title.posterUrl || undefined}
        alt={title.title}
        className="w-full aspect-2/3 object-cover rounded-lg bg-secondary"
      />
      <p className="font-sans text-xs mt-1.5 truncate" style={{ color: 'var(--paper)' }}>{title.title}</p>
    </button>
  )
}

function ListDetail({ list, onBack }: { list: List; onBack: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const memberIds = useAppStore((s) => s.listMemberships[list.id])
  // ⚡ Bolt: Memoize expensive O(N) array filtering to prevent unnecessary calculations on every render
  const memberTitles = useMemo(() => titles.filter((t) => memberIds?.has(t.id)), [titles, memberIds])
  const removeTitleFromList = useAppStore((s) => s.removeTitleFromList)
  const deleteList = useAppStore((s) => s.deleteList)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  return (
    <div>
      <BackToLists onBack={onBack} />

      <div className="flex items-start justify-between gap-4 mb-6">
        <div>
          <h2 className="font-serif text-2xl sm:text-3xl" style={{ color: 'var(--paper)' }}>
            {list.name}
          </h2>
          {list.description && (
            <p className="font-sans text-sm text-muted-foreground mt-1 max-w-[60ch]">{list.description}</p>
          )}
        </div>
        {confirmingDelete ? (
          <div className="flex items-center gap-2 shrink-0">
            <span className="font-sans text-xs text-muted-foreground">Delete this list?</span>
            <Button
              onClick={() => {
                void saveSucceeded(deleteList(list.id)).then((saved) => { if (saved) onBack() })
              }}
              className="h-8 bg-ember hover:bg-ember/80 text-white font-sans text-xs px-3"
            >
              Delete
            </Button>
            <button
              onClick={() => setConfirmingDelete(false)}
              className="icon-btn w-8 h-8 flex items-center justify-center rounded-full"
              aria-label="Cancel delete"
            >
              <X className="w-3.5 h-3.5" aria-hidden="true" />
            </button>
          </div>
        ) : (
          <button
            onClick={() => setConfirmingDelete(true)}
            className="flex items-center gap-1.5 shrink-0 font-mono text-xs rounded-full px-3 py-1.5 border border-(--line) text-muted-foreground hover:text-ember hover:border-ember/30 hover:bg-ember/5 transition-all focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-ember/60"
          >
            <Trash2 className="w-3.5 h-3.5" />
            Delete list
          </button>
        )}
      </div>

      {memberTitles.length === 0 ? (
        <p className="font-sans text-sm text-muted-foreground">
          Nothing in this list yet — add titles from their detail page.
        </p>
      ) : (
        <div className={POSTER_GRID_CLASS}>
          {memberTitles.map((t) => (
            <div key={t.id} className="group relative">
              <PosterLink title={t} onOpen={() => openDetailDrawer(t.id)} />
              <button
                onClick={() => removeTitleFromList(list.id, t.id)}
                aria-label={`Remove ${t.title} from ${list.name}`}
                className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full bg-black/60 backdrop-blur-xs text-white/80 hover:bg-black/80 hover:text-white flex items-center justify-center opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity"
              >
                <X className="w-3.5 h-3.5" aria-hidden="true" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function SmartListDetail({ list, onBack }: { list: SmartList; onBack: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const memberTitles = useMemo(() => smartListMembers(titles, list), [titles, list])
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)

  return (
    <div>
      <BackToLists onBack={onBack} />

      <div className="mb-6">
        <h2 className="font-serif text-2xl sm:text-3xl" style={{ color: 'var(--paper)' }}>
          {list.name}
        </h2>
        <p className="font-sans text-sm text-muted-foreground mt-1 max-w-[60ch]">
          {list.description} Updates automatically from each title's status.
        </p>
      </div>

      {memberTitles.length === 0 ? (
        <p className="font-sans text-sm text-muted-foreground">{list.emptyMessage}</p>
      ) : (
        <div className={POSTER_GRID_CLASS}>
          {memberTitles.map((t) => (
            <PosterLink key={t.id} title={t} onOpen={() => openDetailDrawer(t.id)} />
          ))}
        </div>
      )}
    </div>
  )
}

// ─── Lists ──────────────────────────────────────────────────────────────────

export function Lists() {
  const lists = useAppStore((s) => s.lists)
  const selectedListId = useAppStore((s) => s.selectedListId)
  const openListDetail = useAppStore((s) => s.openListDetail)
  const closeListDetail = useAppStore((s) => s.closeListDetail)

  const selectedList = selectedListId ? lists.find((l) => l.id === selectedListId) ?? null : null
  const selectedSmartList = findSmartList(selectedListId)

  // This view (and its list-detail sub-state) unmounts whenever currentView navigates away
  // from 'lists' — e.g. tapping a different BottomNav tab, which doesn't go through
  // closeListDetail(). Without this, selectedListId would linger globally and leak `?list=`
  // into another view's URL, or silently reopen this list next time Lists remounts.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => () => closeListDetail(), [])

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10 pb-16">
      <div className="mb-[clamp(24px,3.5vw,40px)]">
        <p className="kicker">
          <span className="dot" /> your own reels
        </p>
        <h1 className="display-title text-[clamp(36px,6.5vw,72px)] mt-3.5">
          Your <em>Lists.</em>
        </h1>
        <p className="mt-4 max-w-[60ch] text-[clamp(15px,1.6vw,18px)] text-paper-dim">
          Your watchlist and what you're watching keep themselves up to date. Group everything
          else however you like — a marathon, a ranking, a shortlist for movie night.
        </p>
      </div>

      {selectedSmartList ? (
        <SmartListDetail list={selectedSmartList} onBack={closeListDetail} />
      ) : selectedList ? (
        <ListDetail list={selectedList} onBack={closeListDetail} />
      ) : (
        <div className="space-y-10">
          <section aria-labelledby="smart-lists-heading" className="space-y-4">
            <h2 id="smart-lists-heading" className="kicker">
              <span className="dot" /> automatic
            </h2>
            <SmartListsGrid onOpen={openListDetail} />
          </section>
          <section aria-labelledby="custom-lists-heading" className="space-y-6">
            <h2 id="custom-lists-heading" className="kicker">
              <span className="dot" /> your lists
            </h2>
            {lists.length > 0 && <NewListForm />}
            <ListsGrid onOpen={openListDetail} />
          </section>
        </div>
      )}
    </div>
  )
}
