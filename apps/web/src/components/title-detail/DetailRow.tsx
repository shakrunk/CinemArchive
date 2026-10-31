import { Eyebrow } from 'src/components/ui/typography'

export function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <Eyebrow as="dt" size="xl" tone="muted" className="shrink-0">{label}</Eyebrow>
      <dd className="font-sans text-sm text-foreground text-right min-w-0 wrap-break-word">{value}</dd>
    </div>
  )
}
