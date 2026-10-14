import { useState, type CSSProperties } from 'react'
import { Button } from 'src/components/ui/button'
import {
  DEFAULT_TEXT_PREFERENCES, TEXT_SIZES, saveTextPreferences, textScaleFor,
  useTextPreferences, type TextPreferences as Preferences,
} from 'src/lib/textPreferences'

export function TextPreferences() {
  const saved = useTextPreferences()
  const [message, setMessage] = useState('')
  // A save from another tab discards an obsolete draft; leaving the page discards it too.
  return <>
    <TextPreferencesEditor key={`${saved.family}:${saved.size}`} saved={saved} setMessage={setMessage} />
    <p role="status" className="mt-3 font-sans text-xs text-paper-dim">{message}</p>
  </>
}

function TextPreferencesEditor({ saved, setMessage }: { saved: Preferences; setMessage: (message: string) => void }) {
  const [draft, setDraft] = useState(saved)
  const changed = draft.family !== saved.family || draft.size !== saved.size

  function apply() {
    const persisted = saveTextPreferences(draft)
    setMessage(persisted ? 'Text preferences applied.' : 'Applied for this visit. Browser storage is unavailable, so this choice cannot be saved.')
  }

  return (
    <div className="mt-6 space-y-4 border-t border-border pt-5" aria-label="Text preferences">
      <h3 className="font-serif text-lg text-paper">Text &amp; readability</h3>
      <p className="font-sans text-sm text-paper-dim">Preview a font and size, then apply them across the app. These choices stay on this device and work alongside browser zoom.</p>
      <fieldset>
        <legend className="font-sans text-sm font-medium text-paper mb-2">Font</legend>
        <div className="flex flex-wrap gap-3">
          {([{ value: 'default', label: 'Default · cinematic' }, { value: 'lexend', label: 'Lexend · dyslexia-friendly' }] as const).map((option) => (
            <label key={option.value} className="flex items-center gap-2 rounded-md border border-border p-3 font-sans text-sm cursor-pointer">
              <input type="radio" name="text-family" value={option.value} checked={draft.family === option.value}
                onChange={() => setDraft({ ...draft, family: option.value })} className="accent-amber" />
              {option.label}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend className="font-sans text-sm font-medium text-paper mb-2">Text size</legend>
        <div className="flex flex-wrap gap-3">
          {TEXT_SIZES.map((option) => (
            <label key={option.value} className="flex items-center gap-2 rounded-md border border-border p-3 font-sans text-sm cursor-pointer">
              <input type="radio" name="text-size" value={option.value} checked={draft.size === option.value}
                onChange={() => setDraft({ ...draft, size: option.value })} className="accent-amber" />
              {option.label}
            </label>
          ))}
        </div>
      </fieldset>
      <div aria-label="Text preview" data-text-family={draft.family} className="rounded-lg border border-border bg-secondary/20 p-4 space-y-2"
        style={{ '--text-scale': textScaleFor(draft.size) } as CSSProperties}>
        <p className="font-serif text-xl text-paper">Casablanca</p>
        <p className="font-sans text-sm text-paper-dim">“Here's looking at you, kid.” Preview how titles and body text will look throughout your archive.</p>
        <p className="font-mono text-[11px] text-paper-faint">1942 · Film · 102 minutes</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="button" className="h-auto min-h-10 max-w-full whitespace-normal" onClick={apply} disabled={!changed}>Apply text preferences</Button>
        <Button type="button" className="h-auto min-h-10 max-w-full whitespace-normal" variant="outline" onClick={() => { setDraft(saved); setMessage('Preview canceled.') }} disabled={!changed}>Cancel preview</Button>
        <Button type="button" className="h-auto min-h-10 max-w-full whitespace-normal" variant="ghost" onClick={() => { setDraft(DEFAULT_TEXT_PREFERENCES); setMessage('Defaults previewed. Apply to save.') }}>Reset text defaults</Button>
      </div>
    </div>
  )
}
