import { useState, useRef } from 'react'
import { MetaBadge } from 'src/components/ui/typography'
import { Tag, X } from 'lucide-react'

export function DrawerTagEditor({
  tags,
  isSharedView,
  onChange,
}: {
  tags: string[]
  isSharedView: boolean
  onChange: (tags: string[]) => void
}) {
  const [input, setInput] = useState('')
  const [editing, setEditing] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  function commit() {
    const trimmed = input.trim().replace(/,+$/, '')
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed])
    }
    setInput('')
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commit()
    } else if (e.key === 'Backspace' && input === '' && tags.length > 0) {
      onChange(tags.slice(0, -1))
    } else if (e.key === 'Escape') {
      commit()
      setEditing(false)
    }
  }

  function removeTag(tag: string) {
    onChange(tags.filter((t) => t !== tag))
  }

  if (isSharedView) {
    if (tags.length === 0) return null
    return (
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <MetaBadge key={t} className="border-amber/30 text-amber/80">
            <Tag className="w-2.5 h-2.5 mr-1 inline" />{t}
          </MetaBadge>
        ))}
      </div>
    )
  }

  return (
    <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <span
            key={t}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber/10 border border-amber/20 font-mono text-xs text-amber"
          >
            {t}
            <button
              type="button"
              onClick={() => removeTag(t)}
              className="hover:text-amber-bright transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-full"
              aria-label={`Remove tag ${t}`}
              title={`Remove tag ${t}`}
            >
              <X className="w-2.5 h-2.5" aria-hidden="true" />
            </button>
          </span>
        ))}
        {editing && (
          <input
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKey}
            onBlur={() => { commit(); setEditing(false) }}
            placeholder="tag name…"
            aria-label="New tag name"
            className="px-2 py-0.5 rounded-full bg-secondary border border-amber/30 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-hidden focus:border-amber/60 w-28"
          />
        )}
        {!editing && (
          <button type="button"
            aria-label="Add tag"
            onClick={() => { setEditing(true); setTimeout(() => inputRef.current?.focus(), 0) }}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full border border-dashed border-amber/20 font-mono text-xs text-muted-foreground hover:border-amber/40 hover:text-amber/70 transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
          >
            <Tag className="w-2.5 h-2.5" /> add tag
          </button>
        )}
    </div>
  )
}
