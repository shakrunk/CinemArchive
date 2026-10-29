import { useState, useRef } from 'react'
import { Loader2, Download, Upload, Film, RefreshCw } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { useAppStore } from 'src/store/useAppStore'
import { getErrorMessage } from 'src/lib/utils'
import { exportLibrary, parseImportFile } from 'src/lib/export-import'
import { parseLetterboxdCsv, letterboxdToSyncItems } from 'src/lib/letterboxd-import'
import { resolveSyncItems } from 'src/lib/sync/core'
import { applySyncOutcome } from 'src/lib/sync/apply'
import { captureLibrarySession } from 'src/lib/localSave'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { SyncConnections } from 'src/components/SyncConnections'
import { useLibrarySyncNeedsReview } from 'src/lib/useLibrarySyncNeedsReview'
import { Section } from 'src/components/ui/section'

export function DataSection() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const user = useAppStore((s) => s.user)
  const titles = useAppStore((s) => s.titles)
  const setTitles = useAppStore((s) => s.setTitles)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const outings = useAppStore((s) => s.outings)
  const importLibrary = useAppStore((s) => s.importLibrary)
  const openLibrarySync = useAppStore((s) => s.openLibrarySync)
  const syncNeedsReview = useLibrarySyncNeedsReview()
  const [importing, setImporting] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const lbFileInputRef = useRef<HTMLInputElement>(null)
  const [lbImporting, setLbImporting] = useState(false)
  const [lbProgress, setLbProgress] = useState<{ done: number; total: number } | null>(null)
  const lbCancelRef = useRef(false)

  function handleExport() {
    exportLibrary(titles, outings)
  }

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const checkSession = captureLibrarySession()
    const file = e.target.files?.[0]
    if (!file) return
    if (fileInputRef.current) fileInputRef.current.value = ''

    setImporting(true)
    setMessage(null)
    try {
      const { titles: imported, outings: importedOutings } = await parseImportFile(file)
      checkSession()
      const existingKeys = new Set(titles.map((t) => `${t.tmdbId}:${t.type}`))
      const newTitles = imported.filter((t) => !existingKeys.has(`${t.tmdbId}:${t.type}`))
      const skipped = imported.length - newTitles.length

      if (newTitles.length > 0) {
        const newTitleIds = new Set(newTitles.map((title) => title.id))
        const outingsToInsert = importedOutings.filter((outing) => newTitleIds.has(outing.titleId))
        await importLibrary(newTitles, outingsToInsert)
      }

      const added = newTitles.length
      setMessage({
        type: 'success',
        text: `Imported ${added} title${added !== 1 ? 's' : ''}${skipped > 0 ? `, skipped ${skipped} duplicate${skipped !== 1 ? 's' : ''}` : ''}.`,
      })
    } catch (err) {
      setMessage({ type: 'error', text: getErrorMessage(err, 'Import failed.') })
    } finally {
      setImporting(false)
    }
  }

  // Letterboxd CSV import (KP-045 prototype) — accepts one file from the
  // Letterboxd data-export zip (watched.csv, ratings.csv, diary.csv,
  // watchlist.csv). Each film resolves to TMDB by name+year, so large
  // histories take a while; progress + cancel keep it honest.
  async function handleLetterboxdFile(e: React.ChangeEvent<HTMLInputElement>) {
    const checkSession = captureLibrarySession()
    const file = e.target.files?.[0]
    if (!file) return
    if (lbFileInputRef.current) lbFileInputRef.current.value = ''

    setLbImporting(true)
    setMessage(null)
    setLbProgress(null)
    lbCancelRef.current = false
    try {
      const rows = parseLetterboxdCsv(await file.text())
      if (rows.length === 0) throw new Error('No films found in that CSV.')

      // watchlist.csv rows land on the watchlist; everything else is history.
      const status = /watchlist/i.test(file.name) ? ('watchlist' as const) : ('watched' as const)
      const outcome = await resolveSyncItems(letterboxdToSyncItems(rows, status), {
        library: titles,
        onProgress: (done, total) => setLbProgress({ done, total }),
        isCancelled: () => lbCancelRef.current,
      })
      let added = 0
      let updated = 0
      checkSession()
      if (user) ({ added, updated } = await applySyncOutcome({ userId: user.id, outcome, titles, setTitles, updateTitle }))
      else ({ added, updated } = await useAppStore.getState().applySyncOutcome(outcome))

      const parts = [`Added ${added} film${added !== 1 ? 's' : ''}`]
      if (updated > 0) parts.push(`updated ${updated}`)
      if (outcome.unchanged > 0) parts.push(`${outcome.unchanged} already up to date`)
      if (outcome.unmatched.length > 0) {
        const shown = outcome.unmatched.slice(0, 5).join(', ')
        parts.push(`couldn't match ${outcome.unmatched.length}: ${shown}${outcome.unmatched.length > 5 ? `, +${outcome.unmatched.length - 5} more` : ''}`)
      }
      if (lbCancelRef.current) parts.push('(cancelled early)')
      setMessage({ type: 'success', text: `${parts.join(' · ')}.` })
    } catch (err) {
      setMessage({ type: 'error', text: getErrorMessage(err, 'Letterboxd import failed.') })
    } finally {
      setLbImporting(false)
      setLbProgress(null)
    }
  }

  return (
    <Section
      id="data"
      title="Data & Portability"
      Icon={Download}
      description="Export your entire library as a JSON file, import a previously exported archive, bring your watch history and ratings over from a Letterboxd CSV export, or import from Simkl, Plex and Emby. Duplicates are skipped on import."
    >
      <MessageBanner message={message} />
      {syncNeedsReview && (
        <div className="flex items-center gap-3 max-w-md rounded-lg border border-amber/30 p-3">
          <RefreshCw className="w-3.5 h-3.5 text-amber shrink-0" aria-hidden="true" />
          <p className="flex-1 font-sans text-xs text-paper">This device has library changes or recovery data to review.</p>
          <Button
            onClick={openLibrarySync}
            className="bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors"
          >
            Review sync
          </Button>
        </div>
      )}
      <div className="flex gap-2 max-w-md">
        <Button
          onClick={handleExport}
          className="flex-1 bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          <Download className="w-3.5 h-3.5 text-amber" />
          Export
        </Button>
        <Button
          onClick={() => fileInputRef.current?.click()}
          disabled={importing}
          className="flex-1 bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          {importing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Upload className="w-3.5 h-3.5 text-amber" />}
          Import
        </Button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".json,application/json"
          className="hidden"
          onChange={handleImportFile}
        />
      </div>
      <div className="flex gap-2 max-w-md">
        <Button
          onClick={() => lbFileInputRef.current?.click()}
          disabled={lbImporting}
          className="flex-1 bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
        >
          {lbImporting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Film className="w-3.5 h-3.5 text-amber" />}
          {lbImporting && lbProgress
            ? `Matching ${lbProgress.done}/${lbProgress.total}…`
            : lbImporting
              ? 'Reading CSV…'
              : 'Import from Letterboxd (CSV)'}
        </Button>
        {lbImporting && (
          <Button
            onClick={() => { lbCancelRef.current = true }}
            className="bg-secondary/60 hover:bg-red-500/20 hover:text-red-400 text-paper font-sans text-xs border border-border transition-colors"
          >
            Cancel
          </Button>
        )}
        <input
          ref={lbFileInputRef}
          type="file"
          accept=".csv,text/csv"
          className="hidden"
          onChange={handleLetterboxdFile}
        />
      </div>
      <p className="font-mono text-[10px] text-muted-foreground max-w-md">
        Accepts one file from your Letterboxd data export (watched.csv, ratings.csv,
        diary.csv, or watchlist.csv). Films are matched to TMDB by name and year;
        anything that can't be matched confidently is reported, not guessed.
      </p>
      <SyncConnections />
    </Section>
  )
}
