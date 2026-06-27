import * as DialogPrimitive from '@radix-ui/react-dialog'
import { X } from 'lucide-react'

interface ShortcutEntry {
  keys: string[]
  label: string
}

const SHORTCUTS: { group: string; entries: ShortcutEntry[] }[] = [
  {
    group: 'Navigate',
    entries: [
      { keys: ['1'], label: 'Up Next' },
      { keys: ['2'], label: 'Library' },
      { keys: ['3'], label: 'Ledger' },
    ],
  },
  {
    group: 'Actions',
    entries: [
      { keys: ['n'], label: 'Add a title' },
      { keys: ['/'], label: 'Open command palette' },
      { keys: ['⌘', 'K'], label: 'Open command palette' },
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
      { keys: ['t'], label: 'Toggle dark / light mode' },
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
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="command-overlay" />
        <DialogPrimitive.Content
          className="command-content"
          style={{ maxWidth: '420px' }}
          aria-label="Keyboard shortcuts"
        >
          <DialogPrimitive.Title className="sr-only">Keyboard shortcuts</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            A list of all keyboard shortcuts available in CinemArchive.
          </DialogPrimitive.Description>

          <div className="p-5">
            <div className="flex items-center justify-between mb-5">
              <h2
                className="font-serif text-lg text-paper font-medium"
                style={{ fontVariationSettings: '"opsz" 30' }}
              >
                Keyboard Shortcuts
              </h2>
              <DialogPrimitive.Close
                className="w-7 h-7 flex items-center justify-center text-paper-faint hover:text-paper rounded transition-colors"
                aria-label="Close"
              >
                <X className="w-4 h-4" />
              </DialogPrimitive.Close>
            </div>

            <div className="space-y-5">
              {SHORTCUTS.map(({ group, entries }) => (
                <div key={group}>
                  <div className="font-mono text-[10px] tracking-[0.2em] uppercase text-amber-deep mb-2">
                    {group}
                  </div>
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
              className="mt-5 font-sans text-[11px] text-paper-faint border-t pt-4"
              style={{ borderColor: 'var(--line)' }}
            >
              Shortcuts are disabled when typing in a text field or a dialog is open.
            </p>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
