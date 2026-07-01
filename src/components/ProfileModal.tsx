import { useState, useEffect, useRef } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { Mail, Key, Plus, Trash2, Copy, Check, LogOut, Fingerprint, Shield, Loader2, Download, Upload, Users, UserPlus, Ban, Eye, Inbox, X } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import {
  signInWithEmail,
  signInWithPasskey,
  signOut,
  registerPasskey,
  createSharedKey,
  revokeSharedKey,
  listSharedKeys,
  findUserByEmail,
  sendFriendRequest,
  acceptFriendRequest,
  declineFriendRequest,
  blockFriend,
  listFriendships,
  type FriendshipView,
} from 'src/lib/auth'
import { exportLibrary, parseImportFile } from 'src/lib/export-import'
import { insertTitleToDb, fetchRecommendations, markRecommendationRead, dismissRecommendation, type Recommendation } from 'src/lib/db'

interface ProfileModalProps {
  open: boolean
  onClose: () => void
}

interface SharedKey {
  id: string
  token: string
  label?: string
  expires_at?: string
  is_active: boolean
  created_at: string
  last_used_at?: string
}

export function ProfileModal({ open, onClose }: ProfileModalProps) {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { user, setUser, titles, setTitles, loadFriendLibrary } = useAppStore(
    useShallow((s) => ({
      user: s.user,
      setUser: s.setUser,
      titles: s.titles,
      setTitles: s.setTitles,
      loadFriendLibrary: s.loadFriendLibrary,
    }))
  )

  // Auth state
  const [email, setEmail] = useState('')
  const [loadingAuth, setLoadingAuth] = useState(false)
  const [authMessage, setAuthMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  
  // Shared keys state
  const [sharedKeys, setSharedKeys] = useState<SharedKey[]>([])
  const [loadingKeys, setLoadingKeys] = useState(false)
  const [newKeyLabel, setNewKeyLabel] = useState('')
  const [generatingKey, setGeneratingKey] = useState(false)
  const [copiedKeyId, setCopiedKeyId] = useState<string | null>(null)

  // Export / Import state
  const [importing, setImporting] = useState(false)
  const [dataMessage, setDataMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Friends state
  const [friendEmail, setFriendEmail] = useState('')
  const [sendingRequest, setSendingRequest] = useState(false)
  const [friendMessage, setFriendMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [friendships, setFriendships] = useState<FriendshipView[]>([])
  const [loadingFriends, setLoadingFriends] = useState(false)

  // Recommendations inbox state
  const [recommendations, setRecommendations] = useState<Recommendation[]>([])
  const [loadingRecommendations, setLoadingRecommendations] = useState(false)

  // Fetch shared keys, friends, and recommendations on login
  useEffect(() => {
    if (user && open) {
      loadKeys()
      loadFriendships()
      loadRecommendations()
    }
  }, [user, open])

  async function loadKeys() {
    setLoadingKeys(true)
    try {
      const keys = await listSharedKeys()
      setSharedKeys(keys || [])
    } catch (err) {
      console.error('Failed to load shared keys:', err)
    } finally {
      setLoadingKeys(false)
    }
  }

  // Sign in handlers
  async function handleEmailSignIn(e: React.FormEvent) {
    e.preventDefault()
    if (!email.trim()) return
    
    setLoadingAuth(true)
    setAuthMessage(null)
    try {
      await signInWithEmail(email)
      setAuthMessage({
        type: 'success',
        text: 'Magic link sent! Check your inbox to complete sign-in.',
      })
    } catch (err: any) {
      console.error(err)
      setAuthMessage({
        type: 'error',
        text: err.message || 'Failed to send magic link.',
      })
    } finally {
      setLoadingAuth(false)
    }
  }

  async function handlePasskeySignIn() {
    if (!email.trim()) {
      setAuthMessage({ type: 'error', text: 'Enter your email first to authenticate with a passkey.' })
      return
    }

    setLoadingAuth(true)
    setAuthMessage(null)
    try {
      await signInWithPasskey(email)
      // Note:signInWithPasskey starts challenge. Supabase handles the MFA verification redirect.
      setAuthMessage({
        type: 'success',
        text: 'Passkey verification initiated. Check your browser prompt.',
      })
    } catch (err: any) {
      console.error(err)
      setAuthMessage({
        type: 'error',
        text: err.message || 'Failed to sign in with passkey.',
      })
    } finally {
      setLoadingAuth(false)
    }
  }

  async function handleSignOut() {
    try {
      await signOut()
      setUser(null)
      onClose()
    } catch (err) {
      console.error('Failed to sign out:', err)
    }
  }

  // Key management handlers
  async function handleGenerateKey(e: React.FormEvent) {
    e.preventDefault()
    setGeneratingKey(true)
    try {
      await createSharedKey(newKeyLabel.trim() || undefined)
      setNewKeyLabel('')
      await loadKeys()
    } catch (err) {
      console.error('Failed to generate shared key:', err)
    } finally {
      setGeneratingKey(false)
    }
  }

  async function handleRevokeKey(id: string) {
    if (!confirm('Are you sure you want to revoke this key? Anyone using this link will immediately lose access.')) return
    try {
      await revokeSharedKey(id)
      await loadKeys()
    } catch (err) {
      console.error('Failed to revoke key:', err)
    }
  }

  // Friend handlers
  async function loadFriendships() {
    setLoadingFriends(true)
    try {
      const list = await listFriendships()
      setFriendships(list)
    } catch (err) {
      console.error('Failed to load friendships:', err)
    } finally {
      setLoadingFriends(false)
    }
  }

  async function handleSendFriendRequest(e: React.FormEvent) {
    e.preventDefault()
    if (!friendEmail.trim()) return

    setSendingRequest(true)
    setFriendMessage(null)
    try {
      const found = await findUserByEmail(friendEmail)
      if (!found) {
        setFriendMessage({ type: 'error', text: 'No user found with that email.' })
        return
      }
      await sendFriendRequest(found.user_id)
      setFriendEmail('')
      setFriendMessage({ type: 'success', text: 'Friend request sent.' })
      await loadFriendships()
    } catch (err: any) {
      console.error(err)
      setFriendMessage({ type: 'error', text: err.message || 'Failed to send friend request.' })
    } finally {
      setSendingRequest(false)
    }
  }

  async function handleAcceptFriend(requesterId: string) {
    try {
      await acceptFriendRequest(requesterId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to accept friend request:', err)
    }
  }

  async function handleDeclineFriend(requesterId: string) {
    try {
      await declineFriendRequest(requesterId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to decline friend request:', err)
    }
  }

  function handleViewFriendLibrary(f: FriendshipView) {
    void loadFriendLibrary(f.friend_user_id, f.display_name || f.username || 'Friend')
    onClose()
  }

  async function handleBlockFriend(targetId: string) {
    if (!confirm('Block this user? They will no longer be able to send you friend requests or view your library.')) return
    try {
      await blockFriend(targetId)
      await loadFriendships()
    } catch (err) {
      console.error('Failed to block user:', err)
    }
  }

  // Recommendations handlers
  async function loadRecommendations() {
    setLoadingRecommendations(true)
    try {
      const list = await fetchRecommendations()
      setRecommendations(list)
    } catch (err) {
      console.error('Failed to load recommendations:', err)
    } finally {
      setLoadingRecommendations(false)
    }
  }

  async function handleOpenRecommendation(rec: Recommendation) {
    if (rec.status !== 'unread') return
    setRecommendations((prev) => prev.map((r) => (r.id === rec.id ? { ...r, status: 'read' } : r)))
    try {
      await markRecommendationRead(rec.id)
    } catch (err) {
      console.error('Failed to mark recommendation read:', err)
    }
  }

  async function handleDismissRecommendation(id: string) {
    setRecommendations((prev) => prev.filter((r) => r.id !== id))
    try {
      await dismissRecommendation(id)
    } catch (err) {
      console.error('Failed to dismiss recommendation:', err)
      await loadRecommendations()
    }
  }

  async function handleRegisterPasskey() {
    setLoadingAuth(true)
    setAuthMessage(null)
    try {
      await registerPasskey()
      setAuthMessage({ type: 'success', text: 'Passkey registered successfully! You can now use it to sign in.' })
    } catch (err: any) {
      console.error(err)
      setAuthMessage({ type: 'error', text: err.message || 'Failed to register passkey.' })
    } finally {
      setLoadingAuth(false)
    }
  }

  function handleCopyLink(token: string, keyId: string) {
    const link = `${window.location.origin}${window.location.pathname}?share=${token}`
    navigator.clipboard.writeText(link)
    setCopiedKeyId(keyId)
    setTimeout(() => setCopiedKeyId(null), 2000)
  }

  function handleExport() {
    exportLibrary(titles)
  }

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    if (fileInputRef.current) fileInputRef.current.value = ''

    setImporting(true)
    setDataMessage(null)
    try {
      const imported = await parseImportFile(file)
      const existingKeys = new Set(titles.map((t) => `${t.tmdbId}:${t.type}`))
      const newTitles = imported.filter((t) => !existingKeys.has(`${t.tmdbId}:${t.type}`))
      const skipped = imported.length - newTitles.length

      if (newTitles.length > 0) {
        setTitles([...newTitles, ...titles])
        if (user) {
          await Promise.all(newTitles.map((t) => insertTitleToDb(user.id, t)))
        }
      }

      const added = newTitles.length
      setDataMessage({
        type: 'success',
        text: `Imported ${added} title${added !== 1 ? 's' : ''}${skipped > 0 ? `, skipped ${skipped} duplicate${skipped !== 1 ? 's' : ''}` : ''}.`,
      })
    } catch (err: any) {
      setDataMessage({ type: 'error', text: err.message || 'Import failed.' })
    } finally {
      setImporting(false)
    }
  }

  return (
    <CinemaModal
      open={open}
      onClose={onClose}
      maxWidth="sm:max-w-md"
      title={user ? 'Your Archive Profile' : 'Private Access Sign In'}
      description={user ? 'Manage your account security and shared library links.' : 'Sign in to access your private film archive.'}
    >
      <div className="overflow-y-auto flex-1 scrollbar-thin px-6 py-6">
        <h2 className="font-serif text-xl font-light text-paper mb-5">
          {user ? 'Archive Profile' : 'Sign In'}
        </h2>

        {!user ? (
          /* ─── Logged Out UI ─── */
          <div className="space-y-6">
            <p className="font-sans text-xs text-muted-foreground leading-relaxed">
              Enter your email to sign in. You will receive a passwordless magic link to log in securely.
            </p>

            <form onSubmit={handleEmailSignIn} className="space-y-4">
              <div>
                <label htmlFor="email-input" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                  Email Address
                </label>
                <div className="relative">
                  <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
                  <Input
                    id="email-input"
                    aria-label="Email address"
                    required
                    type="email"
                    placeholder="name@domain.com"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    className="pl-9 bg-secondary/50 border-border"
                  />
                </div>
              </div>

              {authMessage && (
                <div
                  className={cn(
                    'p-3 rounded-lg text-xs font-sans leading-normal border',
                    authMessage.type === 'success'
                      ? 'bg-amber/10 border-amber/30 text-amber'
                      : 'bg-destructive/10 border-destructive/30 text-destructive'
                  )}
                >
                  {authMessage.text}
                </div>
              )}

              <div className="flex gap-2 pt-2">
                <Button
                  type="submit"
                  disabled={loadingAuth}
                  className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
                >
                  {loadingAuth ? (
                    <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                  ) : (
                    <Mail className="w-4 h-4 mr-2" />
                  )}
                  Send Magic Link
                </Button>
                <Button
                  type="button"
                  onClick={handlePasskeySignIn}
                  disabled={loadingAuth}
                  variant="outline"
                  className="border-border text-muted-foreground hover:text-foreground"
                  title="Sign In with Passkey"
                >
                  <Fingerprint className="w-4 h-4" />
                </Button>
              </div>
            </form>
          </div>
        ) : (
          /* ─── Logged In UI ─── */
          <div className="space-y-6">
            {/* User details */}
            <div className="bg-secondary/30 rounded-lg p-4 flex items-center justify-between border border-border">
              <div className="min-w-0">
                <p className="font-sans text-[10px] uppercase tracking-widest text-muted-foreground">Logged in as</p>
                <p className="font-mono text-sm text-paper truncate mt-0.5">{user.email}</p>
              </div>
              <Button
                size="sm"
                onClick={handleSignOut}
                className="bg-secondary/80 hover:bg-destructive hover:text-destructive-foreground text-muted-foreground gap-1.5"
              >
                <LogOut className="w-3.5 h-3.5" />
                Sign Out
              </Button>
            </div>

            {/* Passkeys setup */}
            <div className="space-y-3">
              <h3 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5">
                <Shield className="w-3.5 h-3.5 text-amber" />
                Passkey Security
              </h3>
              <p className="font-sans text-xs text-muted-foreground leading-relaxed">
                Add a biometric passkey (face lock, fingerprint, or PIN) to log in instantly on this device next time without waiting for email links.
              </p>
              <Button
                onClick={handleRegisterPasskey}
                disabled={loadingAuth}
                className="w-full bg-secondary/60 hover:bg-amber/20 hover:text-amber text-paper font-sans text-xs border border-border transition-colors gap-2"
              >
                {loadingAuth ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <Fingerprint className="w-3.5 h-3.5 text-amber" />
                )}
                Register new Passkey
              </Button>
            </div>

            {/* Shared access key manager */}
            <div className="space-y-4 pt-2 border-t border-border">
              <h3 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5">
                <Key className="w-3.5 h-3.5 text-amber" />
                Shared Access Links
              </h3>
              <p className="font-sans text-xs text-muted-foreground leading-relaxed">
                Generate read-only links to share your movie diary with friends. People using these links will see a live poster wall of your archive, but cannot edit.
              </p>

              {/* Generate Key Form */}
              <form onSubmit={handleGenerateKey} className="flex gap-2">
                <Input
                  aria-label="Label for new shared link"
                  placeholder="Label (e.g. My Website, Friends)"
                  value={newKeyLabel}
                  onChange={(e) => setNewKeyLabel(e.target.value)}
                  className="bg-secondary/50 border-border text-xs"
                />
                <Button
                  type="submit"
                  disabled={generatingKey}
                  className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] shrink-0"
                >
                  {generatingKey ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Plus className="w-3.5 h-3.5" />
                  )}
                </Button>
              </form>

              {/* Active Keys List */}
              <div className="space-y-2">
                {loadingKeys ? (
                  <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading links...</div>
                ) : sharedKeys.filter(k => k.is_active).length === 0 ? (
                  <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No active sharing keys generated.</div>
                ) : (
                  sharedKeys
                    .filter(k => k.is_active)
                    .map((k) => (
                      <div key={k.id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
                        <div className="min-w-0">
                          <p className="font-sans text-xs text-paper font-medium truncate">
                            {k.label || 'Unnamed Link'}
                          </p>
                          <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                            Created: {new Date(k.created_at).toLocaleDateString()}
                          </p>
                          {k.last_used_at && (
                            <p className="font-mono text-[9px] text-amber-muted mt-0.5">
                              Last used: {new Date(k.last_used_at).toLocaleDateString()}
                            </p>
                          )}
                        </div>
                        <div className="flex gap-1 shrink-0">
                          <Button
                            size="sm"
                            onClick={() => handleCopyLink(k.token, k.id)}
                            className="bg-secondary hover:bg-secondary-muted text-muted-foreground hover:text-foreground w-7 h-7 p-0 flex items-center justify-center"
                            title="Copy Sharing Link"
                            aria-label="Copy Sharing Link"
                          >
                            {copiedKeyId === k.id ? (
                              <Check className="w-3.5 h-3.5 text-amber" />
                            ) : (
                              <Copy className="w-3.5 h-3.5" />
                            )}
                          </Button>
                          <Button
                            size="sm"
                            onClick={() => handleRevokeKey(k.id)}
                            className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                            title="Revoke Link"
                            aria-label="Revoke Link"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </Button>
                        </div>
                      </div>
                    ))
                )}
              </div>
            </div>

            {/* Friends */}
            <div className="space-y-4 pt-2 border-t border-border">
              <h3 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5">
                <Users className="w-3.5 h-3.5 text-amber" />
                Friends
              </h3>
              <p className="font-sans text-xs text-muted-foreground leading-relaxed">
                Add a friend by email to share your ledger and send them recommendations.
              </p>

              <form onSubmit={handleSendFriendRequest} className="flex gap-2">
                <Input
                  aria-label="Friend's email"
                  type="email"
                  placeholder="friend@example.com"
                  value={friendEmail}
                  onChange={(e) => setFriendEmail(e.target.value)}
                  className="bg-secondary/50 border-border text-xs"
                />
                <Button
                  type="submit"
                  disabled={sendingRequest}
                  className="bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] shrink-0"
                >
                  {sendingRequest ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <UserPlus className="w-3.5 h-3.5" />
                  )}
                </Button>
              </form>

              {friendMessage && (
                <div
                  className={cn(
                    'p-3 rounded-lg text-xs font-sans leading-normal border',
                    friendMessage.type === 'success'
                      ? 'bg-amber/10 border-amber/30 text-amber'
                      : 'bg-destructive/10 border-destructive/30 text-destructive'
                  )}
                >
                  {friendMessage.text}
                </div>
              )}

              <div className="space-y-2">
                {loadingFriends ? (
                  <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading friends...</div>
                ) : friendships.length === 0 ? (
                  <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">No friends yet.</div>
                ) : (
                  friendships.map((f) => (
                    <div key={f.friend_user_id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="font-sans text-xs text-paper font-medium truncate">
                          {f.display_name || f.username || 'Unknown user'}
                        </p>
                        <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                          {f.status === 'pending' && f.requested_by === user.id && 'Request sent'}
                          {f.status === 'pending' && f.requested_by !== user.id && 'Wants to be friends'}
                          {f.status === 'accepted' && 'Friends'}
                          {f.status === 'blocked' && (f.blocked_by === user.id ? 'Blocked' : 'Unavailable')}
                        </p>
                      </div>
                      <div className="flex gap-1 shrink-0">
                        {f.status === 'pending' && f.requested_by !== user.id && (
                          <>
                            <Button
                              size="sm"
                              onClick={() => handleAcceptFriend(f.friend_user_id)}
                              className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                              title="Accept"
                              aria-label="Accept friend request"
                            >
                              <Check className="w-3.5 h-3.5" />
                            </Button>
                            <Button
                              size="sm"
                              onClick={() => handleDeclineFriend(f.friend_user_id)}
                              className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                              title="Decline"
                              aria-label="Decline friend request"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </Button>
                          </>
                        )}
                        {f.status === 'accepted' && (
                          <>
                            <Button
                              size="sm"
                              onClick={() => handleViewFriendLibrary(f)}
                              className="bg-secondary hover:bg-amber/20 hover:text-amber text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                              title="View Library"
                              aria-label={`View ${f.display_name || f.username || 'friend'}'s library`}
                            >
                              <Eye className="w-3.5 h-3.5" />
                            </Button>
                            <Button
                              size="sm"
                              onClick={() => handleBlockFriend(f.friend_user_id)}
                              className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                              title="Block"
                              aria-label="Block user"
                            >
                              <Ban className="w-3.5 h-3.5" />
                            </Button>
                          </>
                        )}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Recommendations inbox */}
            <div className="space-y-4 pt-2 border-t border-border">
              <h3 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5">
                <Inbox className="w-3.5 h-3.5 text-amber" />
                Recommendations
                {recommendations.some((r) => r.status === 'unread') && (
                  <span className="font-mono text-[9px] rounded-full bg-amber/20 text-amber px-1.5 py-0.5">
                    {recommendations.filter((r) => r.status === 'unread').length} new
                  </span>
                )}
              </h3>

              <div className="space-y-2">
                {loadingRecommendations ? (
                  <div className="text-center py-4 text-xs font-mono text-muted-foreground">Loading recommendations...</div>
                ) : recommendations.length === 0 ? (
                  <div className="text-center py-4 text-xs font-sans text-muted-foreground italic">
                    Nothing sent your way yet.
                  </div>
                ) : (
                  recommendations.map((r) => (
                    <div
                      key={r.id}
                      onClick={() => handleOpenRecommendation(r)}
                      className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center gap-3 cursor-default"
                    >
                      {r.posterUrl && (
                        <img
                          src={r.posterUrl}
                          alt=""
                          className="w-8 h-12 object-cover rounded shrink-0"
                        />
                      )}
                      <div className="min-w-0 flex-1">
                        <p className="font-sans text-xs text-paper font-medium truncate flex items-center gap-1.5">
                          {r.status === 'unread' && <span className="w-1.5 h-1.5 rounded-full bg-amber shrink-0" />}
                          {r.title}
                          {r.year ? ` (${r.year})` : ''}
                        </p>
                        <p className="font-mono text-[9px] text-muted-foreground mt-0.5 truncate">
                          from {r.senderDisplayName || r.senderUsername || 'a friend'}
                        </p>
                      </div>
                      <Button
                        size="sm"
                        onClick={(e) => {
                          e.stopPropagation()
                          handleDismissRecommendation(r.id)
                        }}
                        className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center shrink-0"
                        title="Dismiss"
                        aria-label="Dismiss recommendation"
                      >
                        <X className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Data & Portability */}
            <div className="space-y-3 pt-2 border-t border-border">
              <h3 className="font-sans text-xs uppercase tracking-widest text-muted-foreground flex items-center gap-1.5">
                <Download className="w-3.5 h-3.5 text-amber" />
                Data & Portability
              </h3>
              <p className="font-sans text-xs text-muted-foreground leading-relaxed">
                Export your entire library as a JSON file, or import a previously exported archive. Duplicates are skipped on import.
              </p>

              {dataMessage && (
                <div
                  className={cn(
                    'p-3 rounded-lg text-xs font-sans leading-normal border',
                    dataMessage.type === 'success'
                      ? 'bg-amber/10 border-amber/30 text-amber'
                      : 'bg-destructive/10 border-destructive/30 text-destructive'
                  )}
                >
                  {dataMessage.text}
                </div>
              )}

              <div className="flex gap-2">
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
                  {importing ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Upload className="w-3.5 h-3.5 text-amber" />
                  )}
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
            </div>
          </div>
        )}
      </div>
    </CinemaModal>
  )
}
