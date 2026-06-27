/**
 * Animated silk-thread spider web overlay.
 * 8 spokes shoot outward from screen centre (staggered 35ms each),
 * then 4 concentric rings draw in clockwise (staggered 150ms each).
 * The whole SVG fades out after ~1400ms. Total duration: ~2000ms.
 *
 * Mount this component when a mode transition fires; unmount after 2100ms
 * (a 100ms buffer past the CSS animation end).
 */
export function SpiderWebOverlay({ mode }: { mode: 'bw' | 'color' }) {
  const w = window.innerWidth
  const h = window.innerHeight
  const cx = w / 2
  const cy = h / 2
  // Extend past all four corners so the web covers the full viewport
  const spokeLen = Math.hypot(cx, cy) * 1.05

  const SPOKE_COUNT = 8
  const spokes = Array.from({ length: SPOKE_COUNT }, (_, i) => {
    const angle = (i / SPOKE_COUNT) * 2 * Math.PI - Math.PI / 2
    return {
      x2: cx + spokeLen * Math.cos(angle),
      y2: cy + spokeLen * Math.sin(angle),
      len: spokeLen,
      delay: i * 35,
    }
  })

  // Ring radii as fractions of spokeLen; start after last spoke begins (350ms)
  const ringFractions = [0.18, 0.38, 0.58, 0.78]
  const rings = ringFractions.map((frac, j) => {
    const r = spokeLen * frac
    return { r, circ: 2 * Math.PI * r, delay: 350 + j * 150 }
  })

  const stroke = mode === 'bw' ? 'rgba(255,255,255,0.80)' : 'rgba(233,178,102,0.90)'

  return (
    <svg
      aria-hidden="true"
      style={{
        position: 'fixed',
        inset: 0,
        width: '100vw',
        height: '100vh',
        pointerEvents: 'none',
        zIndex: 9998,
        animationName: 'spider-silk-fade',
        animationDuration: '2000ms',
        animationTimingFunction: 'ease',
        animationFillMode: 'forwards',
        ...(mode === 'color'
          ? { filter: 'drop-shadow(0 0 5px rgba(233,100,40,0.65))' }
          : {}),
      }}
    >
      {spokes.map((s, i) => (
        <line
          key={i}
          x1={cx}
          y1={cy}
          x2={s.x2}
          y2={s.y2}
          stroke={stroke}
          strokeWidth="1"
          style={
            {
              strokeDasharray: s.len,
              strokeDashoffset: s.len,
              '--dash-len': String(s.len),
              animationName: 'spider-silk-draw',
              animationDuration: '220ms',
              animationDelay: `${s.delay}ms`,
              animationTimingFunction: 'ease-out',
              animationFillMode: 'forwards',
            } as React.CSSProperties
          }
        />
      ))}
      {rings.map((r, j) => (
        <circle
          key={j}
          cx={cx}
          cy={cy}
          r={r.r}
          fill="none"
          stroke={stroke}
          strokeWidth="0.8"
          style={
            {
              strokeDasharray: r.circ,
              strokeDashoffset: r.circ,
              '--dash-len': String(r.circ),
              animationName: 'spider-silk-draw',
              animationDuration: '200ms',
              animationDelay: `${r.delay}ms`,
              animationTimingFunction: 'ease-out',
              animationFillMode: 'forwards',
            } as React.CSSProperties
          }
        />
      ))}
    </svg>
  )
}
