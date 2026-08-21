import { useState } from 'react'
import { ListChecks, Plus, Trash2, ArrowLeft, X } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { EmptyState } from 'src/components/ui/empty-state'
import { PosterThumb } from 'src/components/ui/poster-thumb'
import type { List } from 'src/store/mockData'

// ─── New list creation ──────────────────────────────────────────────────────

function NewListForm() {
  const createList = useAppStore((s) => s.createList)
  const [name, setName] = useState('')

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    createList(trimmed)
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
        className="shrink-0 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
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
  const memberTitles = titles.filter((t) => memberIds?.has(t.id))

  return (
    <button
      onClick={onOpen}
      className="text-left rounded-xl border p-4 sm:p-5 transition-colors hover:border-amber/40 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
      style={{ borderColor: 'var(--line)', background: 'var(--wash)' }}
    >
      <p className="font-serif text-lg leading-snug truncate" style={{ color: 'var(--paper)' }}>
        {list.name}
      </p>
      {list.description && (
        <p className="font-sans text-xs text-muted-foreground mt-1 line-clamp-2">{list.description}</p>
      )}
      <div className="flex items-center justify-between mt-4">
        <div className="flex -space-x-3">
          {memberTitles.slice(0, 4).map((t) => (
            <div key={t.id} className="ring-2 ring-[var(--wash)] rounded overflow-hidden">
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
      title="No lists yet."
      subtext="Group titles into custom lists — a marathon, a ranked shortlist, anything you like."
      subtextClassName="mb-6"
      ctaLabel="Create your first list"
      onCta={() => createList('My List')}
    />
  )
}

function ListsGrid({ onOpen }: { onOpen: (id: string) => void }) {
  const lists = useAppStore((s) => s.lists)

  if (lists.length === 0) return <ListsEmptyState />

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
      {lists.map((list) => (
        <ListCard key={list.id} list={list} onOpen={() => onOpen(list.id)} />
      ))}
    </div>
  )
}

// ─── List detail ────────────────────────────────────────────────────────────

function ListDetail({ list, onBack }: { list: List; onBack: () => void }) {
  const titles = useAppStore((s) => s.titles)
  const memberIds = useAppStore((s) => s.listMemberships[list.id])
  const memberTitles = titles.filter((t) => memberIds?.has(t.id))
  const removeTitleFromList = useAppStore((s) => s.removeTitleFromList)
  const deleteList = useAppStore((s) => s.deleteList)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  return (
    <div>
      <button
        onClick={onBack}
        className="flex items-center gap-1.5 font-mono text-xs text-muted-foreground hover:text-amber transition-colors mb-4 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded"
      >
        <ArrowLeft className="w-3.5 h-3.5" />
        All lists
      </button>

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
                deleteList(list.id)
                onBack()
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
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        ) : (
          <button
            onClick={() => setConfirmingDelete(true)}
            className="flex items-center gap-1.5 shrink-0 font-mono text-xs rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-ember hover:border-ember/30 hover:bg-ember/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ember/60"
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
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-4">
          {memberTitles.map((t) => (
            <div key={t.id} className="group relative">
              <button onClick={() => openDetailDrawer(t.id)} className="block w-full text-left">
                <img
                  src={t.posterUrl || undefined}
                  alt={t.title}
                  className="w-full aspect-[2/3] object-cover rounded-lg bg-secondary"
                />
                <p className="font-sans text-xs mt-1.5 truncate" style={{ color: 'var(--paper)' }}>{t.title}</p>
              </button>
              <button
                onClick={() => removeTitleFromList(list.id, t.id)}
                aria-label={`Remove ${t.title} from ${list.name}`}
                className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full bg-black/60 backdrop-blur-sm text-white/80 hover:bg-black/80 hover:text-white flex items-center justify-center opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
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

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10 pb-16">
      <div className="mb-[clamp(24px,3.5vw,40px)]">
        <p className="kicker">
          <span className="dot" /> your own reels
        </p>
        <h1 className="display-title text-[clamp(36px,6.5vw,72px)] mt-3.5">
          Custom <em>Lists.</em>
        </h1>
        <p className="mt-4 max-w-[60ch] text-[clamp(15px,1.6vw,18px)] text-paper-dim">
          Group titles however you like — a marathon, a ranking, a shortlist for movie night.
        </p>
      </div>

      {selectedList ? (
        <ListDetail list={selectedList} onBack={closeListDetail} />
      ) : (
        <div className="space-y-6">
          {lists.length > 0 && <NewListForm />}
          <ListsGrid onOpen={openListDetail} />
        </div>
      )}
    </div>
  )
}
