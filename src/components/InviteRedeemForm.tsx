import { useState } from 'react'
import { Ticket, Loader2 } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { redeemInvite, signInWithEmail } from 'src/lib/auth'

interface InviteRedeemFormProps {
  onRedeemed: (message: string) => void
  onError: (message: string) => void
}

/** Shared by the sign-in UI in both Profile.tsx and ProfileModal.tsx — this
 *  app is invite-only, so redeeming a code is the only way a new email
 *  becomes an account (see redeemInvite / redeem-invite Edge Function). */
export function InviteRedeemForm({ onRedeemed, onError }: InviteRedeemFormProps) {
  const [open, setOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!email.trim() || !code.trim()) return
    setLoading(true)
    try {
      await redeemInvite(email.trim(), code.trim())
      await signInWithEmail(email.trim())
      onRedeemed('Account created — check your inbox for a magic link to finish signing in.')
      setOpen(false)
      setCode('')
    } catch (err: any) {
      console.error('Failed to redeem invite:', err)
      onError(err.message || 'Failed to redeem invite code.')
    } finally {
      setLoading(false)
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="font-mono text-xs text-amber/60 hover:text-amber transition-colors"
      >
        Have an invite code?
      </button>
    )
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="space-y-3 max-w-sm rounded-lg border p-3.5"
      style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
    >
      <div className="flex items-center gap-2">
        <Ticket className="w-3.5 h-3.5 text-amber" />
        <span className="font-sans text-xs text-paper">Redeem an invite code</span>
      </div>
      <Input
        aria-label="Email address for invite redemption"
        required
        type="email"
        placeholder="name@domain.com"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        className="bg-secondary/50 border-border text-sm"
      />
      <Input
        aria-label="Invite code"
        required
        value={code}
        onChange={(e) => setCode(e.target.value.toUpperCase())}
        placeholder="INVITE CODE"
        className="bg-secondary/50 border-border text-sm font-mono uppercase"
      />
      <div className="flex gap-2">
        <Button
          type="submit"
          size="sm"
          disabled={loading}
          className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
        >
          {loading && <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" />}
          Create Account
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => setOpen(false)}
          className="border-border text-muted-foreground"
        >
          Cancel
        </Button>
      </div>
    </form>
  )
}
