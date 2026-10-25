import { useState } from 'react'
import { Check, Loader2, X } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { useAppStore } from 'src/store/useAppStore'
import { getErrorMessage } from 'src/lib/utils'
import { confirmJellyfinPlay, type ConfirmResult } from 'src/lib/sync/jellyfinConfirm'
import { dismissJellyfinPlay, useJellyfinPolling, useJellyfinQueue } from 'src/lib/sync/jellyfinWatch'
import type { JellyfinPlay } from 'src/lib/sync/jellyfin'

const OUTCOME_NOTE: Partial<Record<ConfirmResult, string>> = {
  duplicate: 'Already in your history.',
  unmatched: "Couldn't match that to a title.",
}

function describe(play: JellyfinPlay): string {
  if (play.kind === 'movie') return play.year ? `${play.title} (${play.year})` : play.title
  return `${play.title} S${play.seasonNumber}E${play.episodeNumber}${play.episodeName ? ` · ${play.episodeName}` : ''}`
}

/** Asks before any Jellyfin watch is written to the history. Shows one play at a
 *  time; the queue lives on this device until each play is answered. */
export function JellyfinWatchPrompt() {
  useJellyfinPolling()
  const userId = useAppStore((s) => s.user?.id)
  // Friend and shared-link views are read-only; never write a watch into someone else's archive.
  const isOwner = useAppStore((s) => !s.isSharedView && s.viewerContext.kind === 'owner')
  const { pending } = useJellyfinQueue()
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)

  const play = pending[0]
  if (!userId || !isOwner || !play) return null

  async function add(target: JellyfinPlay) {
    if (!userId) return
    setBusy(true)
    setNote(null)
    try {
      const result = await confirmJellyfinPlay(target, userId)
      dismissJellyfinPlay(target.key)
      setNote(OUTCOME_NOTE[result] ?? null)
    } catch (err) {
      // Keep it queued so the user can retry.
      setNote(getErrorMessage(err, "Couldn't add that watch."))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      role="group"
      aria-label="Jellyfin watch"
      className="fixed bottom-20 sm:bottom-6 left-4 sm:left-6 z-220 w-full max-w-xs rounded-lg border border-border bg-card p-3 shadow-lg space-y-2"
    >
      <p className="font-mono text-[10px] uppercase tracking-wider text-amber">
        Jellyfin{pending.length > 1 ? ` · ${pending.length} new watches` : ' · new watch'}
      </p>
      <p className="font-sans text-sm text-paper">
        Add <span className="text-amber">{describe(play)}</span>, watched {play.date}, to your watch history?
      </p>
      {note && <p role="status" className="font-mono text-[10px] text-muted-foreground">{note}</p>}
      <div className="flex gap-2">
        <Button
          onClick={() => add(play)}
          disabled={busy}
          className="bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5 text-amber" />}
          Add to history
        </Button>
        <Button
          onClick={() => { setNote(null); dismissJellyfinPlay(play.key) }}
          disabled={busy}
          className="bg-secondary/60 hover:bg-secondary text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          <X className="w-3.5 h-3.5" />
          Not this one
        </Button>
      </div>
    </div>
  )
}
