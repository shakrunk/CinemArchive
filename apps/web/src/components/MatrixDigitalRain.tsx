import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'

interface MatrixDigitalRainProps {
  onDone: () => void
}

const RAIN_CHARS =
  'ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789'

const DURATION_MS = 4500
const FADE_START_MS = 3200
const FONT_SIZE = 16

export function MatrixDigitalRain({ onDone }: MatrixDigitalRainProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const wrapRef = useRef<HTMLDivElement>(null)
  const onDoneRef = useRef(onDone)
  useEffect(() => { onDoneRef.current = onDone })

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctxMaybe = canvas.getContext('2d')
    if (!ctxMaybe) return
    // Alias as a non-nullable type so TypeScript tracks it correctly in closures
    const ctx: CanvasRenderingContext2D = ctxMaybe

    const W = window.innerWidth
    const H = window.innerHeight
    canvas.width = W
    canvas.height = H

    // Black background
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, W, H)

    const cols = Math.floor(W / FONT_SIZE)
    // Stagger drops: half start in-progress so the screen fills quickly
    const drops: number[] = Array.from({ length: cols }, (_, i) =>
      i % 2 === 0 ? Math.floor(Math.random() * (H / FONT_SIZE)) : Math.floor(Math.random() * -30),
    )

    let animId: number
    const startTime = performance.now()

    function draw(now: number) {
      const elapsed = now - startTime

      if (elapsed >= DURATION_MS) {
        onDoneRef.current()
        return
      }

      if (wrapRef.current && elapsed > FADE_START_MS) {
        const t = (elapsed - FADE_START_MS) / (DURATION_MS - FADE_START_MS)
        wrapRef.current.style.opacity = String(Math.max(0, 1 - t))
      }

      // Faint black overlay creates the trailing fade effect
      ctx.fillStyle = 'rgba(0, 0, 0, 0.05)'
      ctx.fillRect(0, 0, W, H)

      ctx.font = `${FONT_SIZE}px monospace`

      for (let i = 0; i < drops.length; i++) {
        const y = drops[i] * FONT_SIZE
        if (y < 0) {
          drops[i]++
          continue
        }

        const char = RAIN_CHARS[Math.floor(Math.random() * RAIN_CHARS.length)]

        // Lead character is bright; the trail fades naturally through the overlay
        ctx.fillStyle = '#ccffcc'
        ctx.fillText(char, i * FONT_SIZE, y)

        if (y > H && Math.random() > 0.975) {
          drops[i] = Math.floor(Math.random() * -20)
        }
        drops[i]++
      }

      animId = requestAnimationFrame(draw)
    }

    animId = requestAnimationFrame(draw)
    return () => cancelAnimationFrame(animId)
  }, []) // intentional: runs once on mount; onDone accessed via ref

  return createPortal(
    <div
      ref={wrapRef}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 20000,
        pointerEvents: 'none',
        background: '#000',
      }}
    >
      <canvas ref={canvasRef} style={{ display: 'block' }} />
    </div>,
    document.body,
  )
}
