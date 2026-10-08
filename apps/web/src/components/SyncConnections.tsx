import { useCallback, useEffect, useRef, useState } from 'react'
import { Loader2, RefreshCw, Unplug } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { useAppStore } from 'src/store/useAppStore'
import { getErrorMessage } from 'src/lib/utils'
import { resolveSyncItems, type SyncItem, type SyncProvider } from 'src/lib/sync/core'
import {
  applySyncOutcome,
  fetchConnections,
  recordConnection,
  removeConnection,
  type IntegrationConnection,
} from 'src/lib/sync/apply'
import { startSimklAuth, pollSimklAuth, fetchSimklItems, disconnectSimkl, type SimklDeviceCode } from 'src/lib/sync/simkl'
import { startPlexPin, pollPlexPin, listPlexServers, fetchPlexItems } from 'src/lib/sync/plex'
import { embySignIn, fetchEmbyItems } from 'src/lib/sync/emby'

const btn =
  'bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Connect-and-import controls for Simkl, Plex and Emby. Import-only: nothing is
 *  ever written back to these services (sharing back is a separate, explicit opt-in). */
export function SyncConnections() {
  const user = useAppStore((s) => s.user)
  const titles = useAppStore((s) => s.titles)
  const setTitles = useAppStore((s) => s.setTitles)
  const updateTitle = useAppStore((s) => s.updateTitle)

  const [connections, setConnections] = useState<IntegrationConnection[]>([])
  const [busy, setBusy] = useState<SyncProvider | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [message, setMessage] = useState<Message | null>(null)
  const [simklCode, setSimklCode] = useState<SimklDeviceCode | null>(null)
  const [embyForm, setEmbyForm] = useState({ url: '', username: '', password: '' })
  const cancelRef = useRef(false)
  // Plex token is held in memory only — it grants full account access.
  const plexTokenRef = useRef<string | null>(null)

  const refresh = useCallback(async () => {
    try { setConnections(await fetchConnections()) } catch { /* table not migrated yet */ }
  }, [])
  useEffect(() => {
    let live = true
    fetchConnections().then((c) => { if (live) setConnections(c) }).catch(() => { /* table not migrated yet */ })
    return () => { live = false }
  }, [])

  const connected = (p: SyncProvider) => connections.find((c) => c.provider === p)

  async function runImport(provider: SyncProvider, items: SyncItem[]) {
    if (!user) return
    if (items.length === 0) {
      setMessage({ type: 'success', text: 'Nothing to import — no watched or rated items found.' })
      return
    }
    const outcome = await resolveSyncItems(items, {
      library: titles,
      onProgress: (done, total) => setStatus(`Matching ${done}/${total}…`),
      isCancelled: () => cancelRef.current,
    })
    const { added, updated } = await applySyncOutcome({ userId: user.id, outcome, titles, setTitles, updateTitle })
    const parts = [`Added ${added}`, `updated ${updated}`]
    if (outcome.unchanged > 0) parts.push(`${outcome.unchanged} already up to date`)
    if (outcome.unmatched.length > 0) {
      const shown = outcome.unmatched.slice(0, 5).join(', ')
      parts.push(`couldn't match ${outcome.unmatched.length}: ${shown}${outcome.unmatched.length > 5 ? `, +${outcome.unmatched.length - 5} more` : ''}`)
    }
    if (cancelRef.current) parts.push('(cancelled early)')
    setMessage({ type: 'success', text: `${parts.join(' · ')}.` })
    if (provider !== 'simkl') await recordConnection(user.id, provider, { serverUrl: connected(provider)?.serverUrl })
    await refresh()
  }

  async function guarded(provider: SyncProvider, fn: () => Promise<void>, fallback: string) {
    setBusy(provider)
    setMessage(null)
    cancelRef.current = false
    try { await fn() } catch (err) { setMessage({ type: 'error', text: getErrorMessage(err, fallback) }) }
    finally { setBusy(null); setStatus(null); setSimklCode(null) }
  }

  const syncSimkl = () => guarded('simkl', async () => {
    if (!connected('simkl')) {
      const code = await startSimklAuth(false)
      setSimklCode(code)
      const deadline = Date.now() + code.expiresIn * 1000
      let interval = code.interval
      for (;;) {
        if (cancelRef.current || Date.now() > deadline) throw new Error('Simkl sign-in timed out.')
        await sleep(interval * 1000)
        const r = await pollSimklAuth(code.deviceCode, false)
        if ('connected' in r) break
        if (r.slowDown) interval += 5
      }
      setSimklCode(null)
    }
    setStatus('Fetching your Simkl library…')
    await runImport('simkl', await fetchSimklItems())
  }, 'Simkl sync failed.')

  const syncPlex = () => guarded('plex', async () => {
    if (!plexTokenRef.current) {
      const pin = await startPlexPin()
      window.open(pin.authUrl, '_blank', 'noopener')
      setStatus('Approve CinemArchive in the Plex tab that just opened…')
      const deadline = Date.now() + 5 * 60 * 1000
      for (;;) {
        if (cancelRef.current || Date.now() > deadline) throw new Error('Plex sign-in timed out.')
        await sleep(2000)
        const token = await pollPlexPin(pin)
        if (token) { plexTokenRef.current = token; break }
      }
    }
    const servers = await listPlexServers(plexTokenRef.current)
    if (servers.length === 0) throw new Error('No reachable Plex server found on your account.')
    const server = servers[0]
    setStatus(`Reading ${server.name}…`)
    const items = await fetchPlexItems(server.uri, plexTokenRef.current)
    if (user) await recordConnection(user.id, 'plex', { serverUrl: server.uri, accountLabel: server.name })
    await runImport('plex', items)
  }, 'Plex sync failed.')

  const syncEmby = () => guarded('emby', async () => {
    const session = await embySignIn(embyForm.url, embyForm.username, embyForm.password)
    setEmbyForm((f) => ({ ...f, password: '' }))
    setStatus('Reading your Emby library…')
    const items = await fetchEmbyItems(session)
    if (user) await recordConnection(user.id, 'emby', { serverUrl: session.baseUrl, accountLabel: session.username })
    await runImport('emby', items)
  }, 'Emby sync failed.')

  async function disconnect(provider: SyncProvider) {
    if (!user) return
    setMessage(null)
    try {
      if (provider === 'simkl') await disconnectSimkl()
      else await removeConnection(user.id, provider)
      if (provider === 'plex') plexTokenRef.current = null
      await refresh()
      setMessage({ type: 'success', text: 'Disconnected. Previously imported titles stay in your library.' })
    } catch (err) {
      setMessage({ type: 'error', text: getErrorMessage(err, 'Could not disconnect.') })
    }
  }

  const working = busy !== null
  const row = (provider: SyncProvider, label: string, onSync: () => void, extra?: React.ReactNode) => {
    const conn = connected(provider)
    return (
      <div className="rounded-lg border border-border p-3 space-y-2">
        <div className="flex items-center gap-2">
          <span className="font-sans text-xs text-paper flex-1">
            {label}
            {conn?.accountLabel ? ` · ${conn.accountLabel}` : ''}
            {conn?.lastSyncedAt ? ` · last synced ${new Date(conn.lastSyncedAt).toLocaleDateString()}` : ''}
          </span>
          <Button onClick={onSync} disabled={working} className={btn}>
            {busy === provider ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5 text-amber" />}
            {conn ? 'Sync now' : 'Connect & import'}
          </Button>
          {conn && (
            <Button onClick={() => disconnect(provider)} disabled={working} className={btn} aria-label={`Disconnect ${label}`}>
              <Unplug className="w-3.5 h-3.5" />
            </Button>
          )}
        </div>
        {extra}
      </div>
    )
  }

  return (
    <div className="space-y-2 max-w-md">
      <MessageBanner message={message} />
      {row('simkl', 'Simkl', syncSimkl,
        simklCode && (
          <p className="font-mono text-[10px] text-muted-foreground">
            Enter code <span className="text-amber">{simklCode.userCode}</span> at{' '}
            <a className="underline" href={simklCode.verificationUri} target="_blank" rel="noopener noreferrer">{simklCode.verificationUri}</a>
          </p>
        ))}
      {row('plex', 'Plex', syncPlex)}
      {row('emby', 'Emby', syncEmby,
        <div className="grid gap-2">
          <Input placeholder="Server (e.g. emby.example.com)" value={embyForm.url} onChange={(e) => setEmbyForm({ ...embyForm, url: e.target.value })} />
          <div className="flex gap-2">
            <Input placeholder="Username" autoComplete="off" value={embyForm.username} onChange={(e) => setEmbyForm({ ...embyForm, username: e.target.value })} />
            <Input type="password" placeholder="Password" autoComplete="off" value={embyForm.password} onChange={(e) => setEmbyForm({ ...embyForm, password: e.target.value })} />
          </div>
        </div>)}
      {working && (
        <div className="flex items-center gap-2">
          <span className="font-mono text-[10px] text-muted-foreground flex-1">{status ?? 'Working…'}</span>
          <Button onClick={() => { cancelRef.current = true }} className={btn}>Cancel</Button>
        </div>
      )}
      <p className="font-mono text-[10px] text-muted-foreground">
        Import only: your watched titles and ratings are copied in. Nothing is sent back to these services, and existing
        ratings and viewings in CinemArchive are never overwritten. Plex and Emby are contacted straight from your browser,
        so the server must be reachable over https. Plex tokens and Emby passwords are not stored.
      </p>
    </div>
  )
}
