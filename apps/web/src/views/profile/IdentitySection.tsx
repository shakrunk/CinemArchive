import { useState } from 'react'
import { Check, Loader2, Pencil } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { getErrorMessage } from 'src/lib/utils'
import { updateMyProfile, type MyProfile } from 'src/lib/auth'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { Section } from 'src/components/ui/section'
import { Eyebrow } from 'src/components/ui/typography'

const USERNAME_RE = /^[a-z0-9](?:[a-z0-9_-]{1,22}[a-z0-9])?$/

export function IdentitySection({
  profile,
  onProfileChange,
}: {
  profile: MyProfile | null
  onProfileChange: (p: MyProfile) => void
}) {
  // Seeded from the profile on mount — the parent remounts this section (via
  // key) when the profile row first arrives, so lazy init is sufficient.
  const [displayName, setDisplayName] = useState(profile?.display_name ?? '')
  const [username, setUsername] = useState(profile?.username ?? '')
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<Message | null>(null)

  const dirty =
    (profile?.display_name ?? '') !== displayName.trim() ||
    (profile?.username ?? '') !== username.trim().toLowerCase()

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    const nextUsername = username.trim().toLowerCase()
    if (nextUsername && !USERNAME_RE.test(nextUsername)) {
      setMessage({
        type: 'error',
        text: 'Usernames are 3–24 characters: lowercase letters, numbers, hyphens or underscores, starting and ending with a letter or number.',
      })
      return
    }
    setSaving(true)
    setMessage(null)
    try {
      const updated = await updateMyProfile({
        display_name: displayName.trim() || null,
        username: nextUsername || null,
      })
      onProfileChange(updated)
      // Reflect the normalized (trimmed/lowercased) values back into the form.
      setDisplayName(updated.display_name ?? '')
      setUsername(updated.username ?? '')
      setMessage({ type: 'success', text: 'Profile saved.' })
    } catch (err) {
      console.error('Failed to save profile:', err)
      setMessage({ type: 'error', text: getErrorMessage(err, 'Failed to save profile.') })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Section
      id="identity"
      title="Identity"
      Icon={Pencil}
      description="How you appear to friends — in their friend lists, activity feeds, and recommendation inboxes."
    >
      <form onSubmit={handleSave} className="space-y-4 max-w-sm">
        <div>
          <Eyebrow as="label" htmlFor="settings-display-name" size="xl" tone="muted" font="sans" className="block mb-2">
            Display Name
          </Eyebrow>
          <Input
            id="settings-display-name"
            aria-label="Display name"
            placeholder="e.g. Norma Desmond"
            value={displayName}
            maxLength={60}
            onChange={(e) => setDisplayName(e.target.value)}
            className="bg-secondary/50 border-border"
          />
        </div>
        <div>
          <Eyebrow as="label" htmlFor="settings-username" size="xl" tone="muted" font="sans" className="block mb-2">
            Username
          </Eyebrow>
          <Input
            id="settings-username"
            aria-label="Username"
            placeholder="e.g. norma-d"
            value={username}
            maxLength={24}
            onChange={(e) => setUsername(e.target.value)}
            className="bg-secondary/50 border-border font-mono"
          />
          <p className="font-sans text-[11px] text-muted-foreground mt-1.5">
            Optional, unique across CinemArchive. Shown when you haven't set a display name.
          </p>
        </div>

        <MessageBanner message={message} />

        <Button
          type="submit"
          disabled={saving || !dirty}
          className="bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium disabled:opacity-40"
        >
          {saving ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Check className="w-4 h-4 mr-2" />}
          Save Changes
        </Button>
      </form>
    </Section>
  )
}
