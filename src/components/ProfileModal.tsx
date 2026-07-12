import { useState } from 'react'
import { Mail, Fingerprint, Loader2 } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { cn } from 'src/lib/utils'
import { signInWithEmail, signInWithPasskey } from 'src/lib/auth'
import { InviteRedeemForm } from 'src/components/InviteRedeemForm'
import { SegmentedToggle } from 'src/components/ui/segmented-toggle'

interface ProfileModalProps {
  open: boolean
  onClose: () => void
}

// Only ever mounted for logged-out visitors on the landing screen (see App.tsx) —
// once a user is authenticated, the app renders the full-page Profile view instead.
export function ProfileModal({ open, onClose }: ProfileModalProps) {
  const [authMode, setAuthMode] = useState<'signin' | 'signup'>('signin')
  const [email, setEmail] = useState('')
  const [loadingAuth, setLoadingAuth] = useState(false)
  const [authMessage, setAuthMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  function switchAuthMode(next: 'signin' | 'signup') {
    setAuthMode(next)
    setAuthMessage(null)
  }

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
      // Note: signInWithPasskey starts challenge. Supabase handles the MFA verification redirect.
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

  return (
    <CinemaModal
      open={open}
      onClose={onClose}
      maxWidth="sm:max-w-md"
      title={authMode === 'signin' ? 'Private Access Sign In' : 'Redeem Invite Code'}
      description={
        authMode === 'signin'
          ? 'Sign in to access your private film archive.'
          : 'Redeem an invite code to create your account.'
      }
    >
      <div className="overflow-y-auto flex-1 scrollbar-thin px-6 py-6">
        <h2 className="font-serif text-xl font-light text-paper mb-5">
          {authMode === 'signin' ? 'Sign In' : 'Sign Up'}
        </h2>

        <div className="space-y-6">
          <SegmentedToggle
            ariaLabel="Sign in or sign up"
            options={[
              { value: 'signin', label: 'Sign In' },
              { value: 'signup', label: 'Sign Up' },
            ]}
            value={authMode}
            onChange={switchAuthMode}
          />

          {authMode === 'signin' ? (
            <>
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
                    aria-label="Sign In with Passkey"
                  >
                    <Fingerprint className="w-4 h-4" />
                  </Button>
                </div>
              </form>
            </>
          ) : (
            <>
              <p className="font-sans text-xs text-muted-foreground leading-relaxed">
                This is a private, invite-only archive. Enter the email and invite code you were given to create an account.
              </p>

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

              <InviteRedeemForm
                onRedeemed={(text) => setAuthMessage({ type: 'success', text })}
                onError={(text) => setAuthMessage({ type: 'error', text })}
              />
            </>
          )}
        </div>

        <p className="mt-6 font-mono text-[10px] tracking-[0.14em] uppercase text-paper-faint/70 text-center">
          CinemArchive v{__APP_VERSION__}
        </p>
      </div>
    </CinemaModal>
  )
}
