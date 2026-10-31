import { Building2 } from 'lucide-react'
import {
  type CompanyResult,
} from 'src/lib/media'

interface CompanyPickerProps {
  companies: CompanyResult[]
  onSelect: (company: CompanyResult) => void
}

export function CompanyPicker({ companies, onSelect }: CompanyPickerProps) {
  return (
    <div className="max-w-lg mx-auto space-y-2">
      {companies.map((c) => (
        <button
          key={c.id}
          onClick={() => onSelect(c)}
          className="w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-colors hover:border-amber/30"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        >
          <div
            className="w-10 h-10 rounded-lg overflow-hidden shrink-0 border flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--void)' }}
          >
            {c.logoUrl ? (
              <img src={c.logoUrl} alt={c.name} className="w-full h-full object-contain p-1" style={{ filter: 'invert(1)' }} />
            ) : (
              <Building2 className="w-4 h-4 text-paper-faint opacity-40" />
            )}
          </div>
          <div className="min-w-0">
            <p className="font-serif text-sm text-paper">{c.name}</p>
            {c.originCountry && (
              <p className="font-mono text-[10px] text-paper-faint mt-0.5">{c.originCountry}</p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}
