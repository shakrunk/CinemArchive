import { useState, useEffect } from 'react'
import { Plus, Trash2, Copy, Check, Loader2, Ticket } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { fmtDateShort, getErrorMessage } from 'src/lib/utils'
import { useCopyFeedback } from 'src/lib/useCopyFeedback'
import {
  createInviteCode,
  listMyInviteCodes,
  deleteInviteCode,
  type MyProfile,
  type InviteCode,
} from 'src/lib/auth'
import { MessageBanner, type Message } from 'src/components/ui/message-banner'
import { Section } from 'src/components/ui/section'
import { LoadingRow, EmptyRow } from 'src/components/ui/loading-row'

export function InvitesSection({ profile }: { profile: MyProfile | null }) {
  const isOwner = profile?.is_owner ?? false
  const [codes, setCodes] = useState<InviteCode[]>([])
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const { copiedId, copy: copyCode } = useCopyFeedback()
  const [message, setMessage] = useState<Message | null>(null)

  useEffect(() => {
    void loadCodes()
  }, [])

  async function loadCodes() {
    setLoading(true)
    try {
      setCodes(await listMyInviteCodes())
    } catch (err) {
      console.error('Failed to load invite codes:', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleGenerate() {
    setGenerating(true)
    setMessage(null)
    try {
      await createInviteCode()
      await loadCodes()
    } catch (err) {
      console.error('Failed to generate invite code:', err)
      setMessage({ type: 'error', text: getErrorMessage(err, 'Failed to generate invite code.') })
    } finally {
      setGenerating(false)
    }
  }

  async function handleDelete(id: string) {
    try {
      await deleteInviteCode(id)
      await loadCodes()
    } catch (err) {
      console.error('Failed to delete invite code:', err)
    }
  }

  function handleCopy(code: string, id: string) {
    copyCode(code, id)
  }

  const unredeemedCount = codes.filter((c) => !c.redeemed_by).length
  const atCap = !isOwner && codes.length >= 2

  return (
    <Section
      id="invites"
      title="Invites"
      Icon={Ticket}
      description={
        isOwner
          ? 'This is a private, invite-only archive. Generate codes for people you want to give access to.'
          : `This is a private, invite-only archive. You can generate up to 2 invite codes${unredeemedCount > 0 ? ` (${unredeemedCount} unredeemed)` : ''}.`
      }
    >
      <Button
        onClick={handleGenerate}
        disabled={generating || atCap}
        className="bg-amber hover:bg-amber-muted text-(--on-amber) font-sans font-medium w-fit gap-1.5"
        title={atCap ? "You've used both of your invites" : undefined}
      >
        {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
        Generate Invite Code
      </Button>

      <MessageBanner message={message} />

      <div className="space-y-2">
        {loading ? (
          <LoadingRow label="Loading invites..." />
        ) : codes.length === 0 ? (
          <EmptyRow label="No invite codes generated yet." />
        ) : (
          codes.map((c) => (
            <div key={c.id} className="bg-secondary/20 rounded-lg p-3 border border-border flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-mono text-sm text-paper font-medium tracking-wider">{c.code}</p>
                <p className="font-mono text-[9px] text-muted-foreground mt-0.5">
                  {c.redeemed_by
                    ? `Redeemed ${c.redeemed_at ? fmtDateShort(c.redeemed_at) : ''}`
                    : `Created ${fmtDateShort(c.created_at)}`}
                </p>
              </div>
              <div className="flex gap-1 shrink-0">
                {!c.redeemed_by && (
                  <>
                    <Button
                      size="sm"
                      onClick={() => handleCopy(c.code, c.id)}
                      className="bg-secondary hover:bg-secondary-muted text-muted-foreground hover:text-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Copy Invite Code"
                      aria-label="Copy Invite Code"
                    >
                      {copiedId === c.id ? <Check className="w-3.5 h-3.5 text-amber" /> : <Copy className="w-3.5 h-3.5" />}
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => handleDelete(c.id)}
                      className="bg-secondary hover:bg-destructive hover:text-destructive-foreground text-muted-foreground w-7 h-7 p-0 flex items-center justify-center"
                      title="Delete Invite Code"
                      aria-label="Delete Invite Code"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                  </>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </Section>
  )
}
