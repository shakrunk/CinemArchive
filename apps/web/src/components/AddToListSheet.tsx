import { useState } from 'react'
import { Check, Plus } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { Chip } from 'src/components/ui/chip'
import { ModalBackdrop } from 'src/components/ui/modal-backdrop'
import { ModalCloseButton } from 'src/components/ui/modal-close-button'
import { useModalFocusAndEscape } from 'src/lib/useModalFocusAndEscape'
import { useAppStore } from 'src/store/useAppStore'
import { Eyebrow } from 'src/components/ui/typography'

interface AddToListSheetProps {
  titleId: string
  titleName: string
  onClose: () => void
}

// Templated on ShareScopeEditor's Chip-toggle multi-select. Each toggle writes
// immediately (addTitleToList/removeTitleFromList are already fire-and-forget
// optimistic store actions), so this needs no local "pending changes" state or
// batched Save button — unlike ShareScopeEditor, which only writes on Save.
export function AddToListSheet({ titleId, titleName, onClose }: AddToListSheetProps) {
  const closeButtonRef = useModalFocusAndEscape<HTMLButtonElement>(onClose)
  const lists = useAppStore((s) => s.lists)
  const listMemberships = useAppStore((s) => s.listMemberships)
  const addTitleToList = useAppStore((s) => s.addTitleToList)
  const removeTitleFromList = useAppStore((s) => s.removeTitleFromList)
  const createList = useAppStore((s) => s.createList)

  const [newListName, setNewListName] = useState('')

  function toggle(listId: string) {
    if (listMemberships[listId]?.has(titleId)) removeTitleFromList(listId, titleId)
    else addTitleToList(listId, titleId)
  }

  function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    const name = newListName.trim()
    if (!name) return
    const list = createList(name)
    addTitleToList(list.id, titleId)
    setNewListName('')
  }

  return (
    <ModalBackdrop onClose={onClose} ariaLabel={`Add "${titleName}" to a list`}>
      <div
        className="relative w-full max-w-sm rounded-xl overflow-hidden flex flex-col"
        style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)', maxHeight: '85vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        <ModalCloseButton
          ref={closeButtonRef}
          onClick={onClose}
          ariaLabel="Close add to list"
          className="top-3 right-3"
        />

        <div className="px-5 pt-5 pb-4 shrink-0">
          <div
            className="font-mono uppercase tracking-widest"
            style={{ fontSize: '9px', color: 'var(--paper-faint)', letterSpacing: '0.14em' }}
          >
            Add to list
          </div>
          <div className="font-serif text-base leading-snug mt-0.5 truncate" style={{ color: 'var(--paper)' }}>
            {titleName}
          </div>
        </div>

        <div className="overflow-y-auto flex-1 px-5 pb-5 space-y-4">
          {lists.length > 0 && (
            <div>
              <Eyebrow as="h4" size="md" className="mb-2">Your lists</Eyebrow>
              <div className="flex flex-wrap gap-1.5">
                {lists.map((l) => {
                  const active = listMemberships[l.id]?.has(titleId) ?? false
                  return (
                    <Chip key={l.id} active={active} onClick={() => toggle(l.id)}>
                      {active && <Check className="w-3 h-3 mr-1 inline" />}
                      {l.name}
                    </Chip>
                  )
                })}
              </div>
            </div>
          )}

          <form onSubmit={handleCreate} className="flex gap-2">
            <Input
              value={newListName}
              onChange={(e) => setNewListName(e.target.value)}
              placeholder="New list name…"
              aria-label="New list name"
              className="h-9 text-sm"
            />
            <Button
              type="submit"
              aria-label="Create list"
              disabled={!newListName.trim()}
              className="h-9 shrink-0 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium px-3"
            >
              <Plus className="w-4 h-4" />
            </Button>
          </form>
        </div>
      </div>
    </ModalBackdrop>
  )
}
