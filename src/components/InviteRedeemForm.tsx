import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { redeemInvite, signInWithEmail } from 'src/lib/auth'

interface InviteRedeemFormProps {
  onRedeemed: (message: string) => void
  onError: (message: string) => void
}

/** Shared by the sign-in UI in both Profile.tsx and ProfileModal.tsx — this
 *  app is invite-only, so redeeming a code is the only way a new email
 *  becomes an account (see redeemInvite / redeem-invite Edge Function).
 *  The parent owns the sign-in-vs-sign-up tab toggle; this always renders
 *  the full form. */
export function InviteRedeemForm({ onRedeemed, onError }: InviteRedeemFormProps) {
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
      setCode('')
    } catch (err: any) {
      console.error('Failed to redeem invite:', err)
      onError(err.message || 'Failed to redeem invite code.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label htmlFor="invite-email-input" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
          Email Address
        </label>
        <Input
          id="invite-email-input"
          aria-label="Email address for invite redemption"
          required
          type="email"
          placeholder="name@domain.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="bg-secondary/50 border-border"
        />
      </div>
      <div>
        <label htmlFor="invite-code-input" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
          Invite Code
        </label>
        <Input
          id="invite-code-input"
          aria-label="Invite code"
          required
          value={code}
          onChange={(e) => setCode(e.target.value.toUpperCase())}
          placeholder="INVITE CODE"
          className="bg-secondary/50 border-border font-mono uppercase"
        />
      </div>
      <Button
        type="submit"
        disabled={loading}
        className="w-full bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
      >
        {loading && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
        Create Account
      </Button>
    </form>
  )
}
