import { cn } from 'src/lib/utils'

export interface Message {
  type: 'success' | 'error'
  text: string
}

export function MessageBanner({ message }: { message: Message | null }) {
  if (!message) return null
  return (
    <div
      className={cn(
        'p-3 rounded-lg text-xs font-sans leading-normal border',
        message.type === 'success'
          ? 'bg-amber/10 border-amber/30 text-amber'
          : 'bg-destructive/10 border-destructive/30 text-destructive'
      )}
    >
      {message.text}
    </div>
  )
}
