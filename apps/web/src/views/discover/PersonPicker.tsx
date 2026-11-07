import { User } from 'lucide-react'
import {
  type PersonResult,
} from 'src/lib/media'

interface PersonPickerProps {
  persons: PersonResult[]
  onSelect: (person: PersonResult) => void
}

export function PersonPicker({ persons, onSelect }: PersonPickerProps) {
  return (
    <div className="max-w-lg mx-auto space-y-2">
      {persons.map((p) => (
        <button
          key={p.id}
          onClick={() => onSelect(p)}
          className="w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-colors hover:border-amber/30"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        >
          <div
            className="w-10 h-10 rounded-full overflow-hidden shrink-0 border flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--void)' }}
          >
            {p.profileUrl ? (
              <img src={p.profileUrl} alt={p.name} className="w-full h-full object-cover" />
            ) : (
              <User className="w-4 h-4 text-paper-faint opacity-40" />
            )}
          </div>
          <div className="min-w-0">
            <p className="font-serif text-sm text-paper">{p.name}</p>
            {p.department && (
              <p className="font-mono text-[10px] text-amber/60 mt-0.5">{p.department}</p>
            )}
            {p.knownFor && (
              <p className="font-mono text-[10px] text-paper-faint truncate mt-0.5">{p.knownFor}</p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}
