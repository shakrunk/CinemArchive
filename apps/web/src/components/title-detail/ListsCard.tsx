import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { Chip } from 'src/components/ui/chip'
import { SectionCard } from './SectionCard'

// Lists is private-only — never rendered for a shared/friend viewer (isSharedView).
export function ListsCard({ titleId, onOpenPicker }: { titleId: string; onOpenPicker: () => void }) {
  // Atomic selectors + local useMemo, not `s.listsForTitle(titleId)` directly as the selector —
  // that store method allocates a fresh array every call, so useSyncExternalStore's snapshot
  // comparison never sees it as equal and re-renders forever ("Maximum update depth exceeded",
  // which takes the whole app down since nothing catches it).
  const allLists = useAppStore((s) => s.lists)
  const listMemberships = useAppStore((s) => s.listMemberships)
  const lists = useMemo(
    () => allLists.filter((l) => listMemberships[l.id]?.has(titleId)),
    [allLists, listMemberships, titleId]
  )
  return (
    <SectionCard
      title="Lists"
      action={
        <button type="button"
          onClick={onOpenPicker}
          className="font-mono text-xs text-amber hover:underline focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded"
        >
          {lists.length > 0 ? 'Edit' : 'Add to list'}
        </button>
      }
    >
      {lists.length === 0 ? (
        <p className="font-sans text-xs text-muted-foreground">Not in any lists yet.</p>
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {lists.map((l) => (
            <Chip key={l.id} active onClick={onOpenPicker}>{l.name}</Chip>
          ))}
        </div>
      )}
    </SectionCard>
  )
}
