import * as DialogPrimitive from '@radix-ui/react-dialog'
import { modKey } from 'src/lib/utils'
import { useVisibleNavItems } from 'src/store/useAppStore'
import { NAV_ITEM_LABELS } from 'src/lib/navigation'
import { ModalCloseButton } from 'src/components/ui/modal-close-button'
import { Eyebrow } from 'src/components/ui/typography'

interface ShortcutEntry {
  keys: string[]
  label: string
}

const OTHER_GROUPS: { group: string; entries: ShortcutEntry[] }[] = [
  {
    group: 'Actions',
    entries: [
      { keys: ['n'], label: 'Add a title' },
      { keys: ['/'], label: 'Open command palette' },
      { keys: [modKey, 'K'], label: 'Open command palette' },
      { keys: [modKey, ','], label: 'Open Settings' },
    ],
  },
  {
    group: 'Library layout',
    entries: [
      { keys: ['g'], label: 'Poster wall (grid)' },
      { keys: ['l'], label: 'Ledger list' },
    ],
  },
  {
    group: 'Appearance',
    entries: [
      { keys: ['t'], label: 'Toggle dark / light mode (exits System)' },
    ],
  },
  {
    group: 'Help',
    entries: [
      { keys: ['?'], label: 'Show this help' },
      { keys: ['Esc'], label: 'Close any dialog' },
    ],
  },
]

interface KeyboardShortcutsHelpProps {
  open: boolean
  onClose: () => void
}

export function KeyboardShortcutsHelp({ open, onClose }: KeyboardShortcutsHelpProps) {
  const visibleNav = useVisibleNavItems()
  const navigateEntries: ShortcutEntry[] = [
    ...visibleNav.map((id, i) => ({ keys: [String(i + 1)], label: NAV_ITEM_LABELS[id] })),
    { keys: [String(visibleNav.length + 1)], label: 'Profile & Settings' },
  ]
  const SHORTCUTS = [{ group: 'Navigate', entries: navigateEntries }, ...OTHER_GROUPS]

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="command-overlay" />
        <DialogPrimitive.Content
          className="command-content"
          // Wider than the palette it shares .command-content with, so the
          // groups below have room to sit two-up instead of fully stacked.
          style={{ width: 'min(92vw, 680px)', maxWidth: 'none' }}
          aria-label="Keyboard shortcuts"
        >
          <DialogPrimitive.Title className="sr-only">Keyboard shortcuts</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            A list of all keyboard shortcuts available in CinemArchive.
          </DialogPrimitive.Description>

          <div className="flex items-center justify-between p-5 pb-4 shrink-0">
            <h2
              className="font-serif text-lg text-paper font-medium"
              style={{ fontVariationSettings: '"opsz" 30' }}
            >
              Keyboard Shortcuts
            </h2>
            <DialogPrimitive.Close asChild>
              <ModalCloseButton ariaLabel="Close" className="static" />
            </DialogPrimitive.Close>
          </div>

          {/* Multi-column rather than a grid: the groups have uneven heights, and
              CSS columns balance them instead of leaving one column ragged. */}
          <div className="flex-1 min-h-0 overflow-y-auto px-5 pb-1 sm:columns-2 sm:gap-8">
            {SHORTCUTS.map(({ group, entries }) => (
              <div key={group} className="mb-5 break-inside-avoid">
                <Eyebrow as="div" size="md" tone="accent" className="mb-2">
                  {group}
                </Eyebrow>
                <div className="space-y-0.5">
                  {entries.map(({ keys, label }) => (
                    <div key={label} className="flex items-center justify-between py-1.5">
                      <span className="font-sans text-sm text-paper-dim">{label}</span>
                      <div className="flex items-center gap-1">
                        {keys.map((k, i) => (
                          <kbd
                            key={i}
                            className="font-mono text-[11px] text-paper-faint border rounded px-1.5 py-0.5 min-w-[24px] text-center leading-none"
                            style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
                          >
                            {k}
                          </kbd>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>

          <p
            className="shrink-0 p-5 pt-4 font-sans text-[11px] text-paper-faint border-t"
            style={{ borderColor: 'var(--line)' }}
          >
            Shortcuts are disabled when typing in a text field or a dialog is open.
          </p>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
