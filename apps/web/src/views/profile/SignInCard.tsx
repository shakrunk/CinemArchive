import { useState } from 'react'
import { Mail, Loader2 } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { cn, getErrorMessage } from 'src/lib/utils'
import { signInWithEmail } from 'src/lib/auth'
import { InviteRedeemForm } from 'src/components/InviteRedeemForm'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { Eyebrow } from 'src/components/ui/typography'

function AuthModeTabs({ mode, onChange }: { mode: 'signin' | 'signup'; onChange: (m: 'signin' | 'signup') => void }) {
  return (
    <div className="grid grid-cols-2 gap-2 max-w-sm" role="tablist" aria-label="Sign in or sign up">
      {(['signin', 'signup'] as const).map((m) => (
        <button
          key={m}
          type="button"
          role="tab"
          aria-selected={mode === m}
          onClick={() => onChange(m)}
          className={cn(
            'rounded-lg border py-2.5 font-sans text-sm font-medium transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
            mode === m
              ? 'border-amber/50 bg-amber/10 text-amber'
              : 'border-border bg-secondary/20 text-muted-foreground hover:border-amber/25'
          )}
        >
          {m === 'signin' ? 'Sign In' : 'Sign Up'}
        </button>
      ))}
    </div>
  )
}

export function SignInCard() {
  const [mode, setMode] = useState<'signin' | 'signup'>('signin')
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

  function switchMode(next: 'signin' | 'signup') {
    setMode(next)
    setMessage(null)
  }

  async function handleEmailSignIn(e: React.FormEvent) {
    e.preventDefault()
    if (!email.trim()) return
    setLoading(true)
    setMessage(null)
    try {
      await signInWithEmail(email)
      setMessage({ type: 'success', text: 'Magic link sent! Check your inbox to complete sign-in.' })
    } catch (err) {
      console.error(err)
      setMessage({ type: 'error', text: getErrorMessage(err, 'Failed to send magic link.') })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-4">
      <AuthModeTabs mode={mode} onChange={switchMode} />

      {mode === 'signin' ? (
        <form onSubmit={handleEmailSignIn} className="space-y-4">
          <p className="font-sans text-xs text-muted-foreground leading-relaxed">
            Enter your email to sign in. You will receive a passwordless magic link to log in securely.
          </p>
          <div>
            <Eyebrow as="label" htmlFor="settings-email-input" size="xl" tone="muted" font="sans" className="block mb-2">
              Email Address
            </Eyebrow>
            <div className="relative max-w-sm">
              <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
              <Input
                id="settings-email-input"
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

          <MessageBanner message={message} />

          <div className="flex gap-2 max-w-sm">
            <Button
              type="submit"
              disabled={loading}
              className="flex-1 bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium"
            >
              {loading ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Mail className="w-4 h-4 mr-2" />}
              Send Magic Link
            </Button>
          </div>
        </form>
      ) : (
        <div className="space-y-4 max-w-sm">
          <p className="font-sans text-xs text-muted-foreground leading-relaxed">
            This is a private, invite-only archive. Enter the email and invite code you were given to create an account.
          </p>
          <MessageBanner message={message} />
          <InviteRedeemForm
            onRedeemed={(text) => setMessage({ type: 'success', text })}
            onError={(text) => setMessage({ type: 'error', text })}
          />
        </div>
      )}
    </div>
  )
}
