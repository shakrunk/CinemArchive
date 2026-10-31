import { recapFields, recapSummary, type MoviegoingRecap } from 'src/store/moviegoingRules'

// The yearly recap as a share-ready 1080x1350 card (docs/android-contracts/moviegoing-recap.md).
// The lines come from recapFields, like the on-screen card, so the image never disagrees with it.
// The palette is the app's dark-gold one, fixed so the card looks the same whatever theme the
// sharer runs.

const WIDTH = 1080
const HEIGHT = 1350
const MARGIN = 90
const BACKGROUND = '#0b0907'
const GOLD = '#e9b266'
const INK = '#f3ead9'
const MUTED = '#b5a58c'

/** Draws the recap onto a canvas, or returns null where canvas drawing is unavailable. */
export function renderRecapCanvas(recap: MoviegoingRecap, money: (amount: number) => string): HTMLCanvasElement | null {
  if (typeof document === 'undefined') return null
  const canvas = document.createElement('canvas')
  canvas.width = WIDTH
  canvas.height = HEIGHT
  const ctx = canvas.getContext('2d')
  if (!ctx) return null

  ctx.fillStyle = BACKGROUND
  ctx.fillRect(0, 0, WIDTH, HEIGHT)
  ctx.strokeStyle = GOLD
  ctx.globalAlpha = 0.55
  ctx.lineWidth = 4
  ctx.beginPath()
  ctx.roundRect(40, 40, WIDTH - 80, HEIGHT - 80, 36)
  ctx.stroke()
  ctx.globalAlpha = 1

  const maxWidth = WIDTH - 2 * MARGIN
  ctx.textBaseline = 'alphabetic'
  const text = (value: string, y: number, size: number, color: string, weight = 'normal', family = 'sans-serif') => {
    ctx.font = `${weight} ${size}px ${family}`
    ctx.fillStyle = color
    let shown = value
    while (shown.length > 1 && ctx.measureText(shown).width > maxWidth) shown = shown.slice(0, -1)
    ctx.fillText(shown === value ? shown : `${shown.trimEnd()}…`, MARGIN, y)
  }

  text('CINEMARCHIVE', 170, 34, MUTED)
  text(String(recap.year), 360, 220, GOLD, 'bold', 'monospace')
  text('AT THE MOVIES', 440, 40, INK)
  text(String(recap.tripCount), 680, 240, INK, 'bold', 'monospace')
  text(recap.tripCount === 1 ? 'trip' : 'trips', 750, 56, MUTED)

  let y = 870
  for (const field of recapFields(recap, money)) {
    text(field.label.toUpperCase(), y, 32, MUTED)
    text(field.value, y + 66, 60, INK)
    y += 140
  }
  return canvas
}

function toBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/png'))
}

/** Shares the recap image through the system share sheet where the browser can share files;
 *  otherwise downloads the PNG. Nothing is uploaded by the app. Returns how it was delivered. */
export async function shareRecap(recap: MoviegoingRecap, money: (amount: number) => string): Promise<'shared' | 'downloaded' | 'unavailable'> {
  const canvas = renderRecapCanvas(recap, money)
  const blob = canvas ? await toBlob(canvas) : null
  if (!blob) return 'unavailable'
  const name = `moviegoing-${recap.year}.png`
  const file = new File([blob], name, { type: 'image/png' })
  const text = recapSummary(recap, money)
  if (typeof navigator !== 'undefined' && navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], text })
      return 'shared'
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return 'shared' // the person closed the sheet
    }
  }
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
  return 'downloaded'
}
