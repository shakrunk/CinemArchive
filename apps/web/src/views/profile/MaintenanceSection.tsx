import { useState, useRef } from 'react'
import { Loader2, RefreshCw } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { useAppStore } from 'src/store/useAppStore'
import { titleToSearchResult, fetchRefreshedTitlePatch } from 'src/lib/refreshMetadata'
import { captureLibrarySession } from 'src/lib/localSave'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { Section } from 'src/components/ui/section'

export function MaintenanceSection() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const user = useAppStore((s) => s.user)
  const titles = useAppStore((s) => s.titles)
  const updateTitle = useAppStore((s) => s.updateTitleMetadata)
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState<Message | null>(null)
  const cancelRef = useRef(false)

  const eligible = titles.filter((t) => t.tmdbId)

  async function handleRefreshAll() {
    const checkSession = captureLibrarySession()
    if (
      !confirm(
        `Refresh metadata for ${eligible.length} title${eligible.length !== 1 ? 's' : ''} from TMDB/OMDb? This re-pulls posters, synopses, and ratings for your whole library and can take a few minutes.`
      )
    ) {
      return
    }

    setRunning(true)
    setProgress(0)
    setMessage(null)
    cancelRef.current = false

    const failed: string[] = []
    let done = 0

    for (const title of eligible) {
      if (cancelRef.current) break
      try {
        const patch = await fetchRefreshedTitlePatch(title, titleToSearchResult(title), user?.id)
        checkSession()
        await updateTitle(title.id, patch)
      } catch (err) {
        console.error(`Failed to refresh metadata for "${title.title}":`, err)
        failed.push(title.title)
      }
      done++
      setProgress(done)
    }

    setRunning(false)
    const cancelled = cancelRef.current
    const skipped = titles.length - eligible.length
    const parts = [`Refreshed ${done - failed.length}/${eligible.length} titles${cancelled ? ' (cancelled)' : ''}.`]
    if (failed.length > 0) parts.push(`Failed: ${failed.slice(0, 5).join(', ')}${failed.length > 5 ? '…' : ''}.`)
    if (skipped > 0) parts.push(`Skipped ${skipped} title${skipped !== 1 ? 's' : ''} not linked to TMDB.`)
    setMessage({ type: failed.length > 0 ? 'error' : 'success', text: parts.join(' ') })
  }

  function handleCancel() {
    cancelRef.current = true
  }

  return (
    <Section
      id="maintenance"
      title="Maintenance"
      Icon={RefreshCw}
      description="Re-pull metadata for every title in your library at once — useful after a schema change to backfill new fields, or when data has just gone stale. Your ratings, notes, and viewing history are untouched."
    >
      <MessageBanner message={message} />
      <div className="flex items-center gap-2 flex-wrap">
        <Button
          onClick={handleRefreshAll}
          disabled={running || eligible.length === 0}
          className="bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2 disabled:opacity-40"
        >
          {running ? (
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
          ) : (
            <RefreshCw className="w-3.5 h-3.5 text-amber" />
          )}
          {running ? `Refreshing ${progress}/${eligible.length}…` : 'Refresh All Metadata'}
        </Button>
        {running && (
          <Button
            onClick={handleCancel}
            className="bg-secondary/40 hover:bg-destructive hover:text-destructive-foreground text-muted-foreground font-sans text-xs"
          >
            Cancel
          </Button>
        )}
      </div>
      {eligible.length === 0 && (
        <p className="font-sans text-[11px] text-muted-foreground">
          No titles are linked to TMDB yet — link one from its Refresh Metadata panel first.
        </p>
      )}
    </Section>
  )
}
