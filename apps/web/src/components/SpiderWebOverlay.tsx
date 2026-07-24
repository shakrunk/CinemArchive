import { createPortal } from 'react-dom'

/**
 * Animated silk-thread spider web overlay.
 * 8 spokes shoot outward from screen centre (staggered 35ms each),
 * then 4 concentric rings draw in clockwise (staggered 150ms each).
 * Rings are quadratic bezier arcs that bow outward between spokes,
 * matching the natural curvature of a real spider web.
 * The whole SVG fades out after ~1400ms. Total duration: ~2000ms.
 *
 * Rendered via a portal into document.body so it sits above every stacking
 * context, including the CinemaModal dialog.
 *
 * Mount this component when a mode transition fires; unmount after 2100ms
 * (a 100ms buffer past the CSS animation end).
 */
export default function SpiderWebOverlay({ mode }: { mode: 'bw' | 'color' }) {
  const w = window.innerWidth
  const h = window.innerHeight
  const cx = w / 2
  const cy = h / 2
  // Extend past all four corners so the web covers the full viewport
  const spokeLen = Math.hypot(cx, cy) * 1.05

  const SPOKE_COUNT = 8
  // Control points pulled INWARD (toward center) for realistic web curvature.
  // Values < ~1.076 produce inward bowing; circles would be ~1.076.
  const BOW = 0.75

  const angles = Array.from({ length: SPOKE_COUNT }, (_, i) =>
    (i / SPOKE_COUNT) * 2 * Math.PI - Math.PI / 2
  )

  const spokes = angles.map((angle, i) => ({
    x2: cx + spokeLen * Math.cos(angle),
    y2: cy + spokeLen * Math.sin(angle),
    len: spokeLen,
    delay: i * 35,
  }))

  // Ring radii as fractions of spokeLen; start after last spoke begins (350ms).
  // Each ring is 8 quadratic bezier segments connecting adjacent spoke intersections,
  // with control points pushed outward (away from centre) to mimic a real web.
  const ringFractions = [0.18, 0.38, 0.58, 0.78]
  const rings = ringFractions.map((frac, j) => {
    const r = spokeLen * frac
    const pts = angles.map(a => ({
      x: cx + r * Math.cos(a),
      y: cy + r * Math.sin(a),
    }))
    const parts = [`M ${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`]
    for (let i = 0; i < SPOKE_COUNT; i++) {
      // Midpoint angle between spoke i and spoke i+1 (always π/SPOKE_COUNT ahead)
      const midA = angles[i] + Math.PI / SPOKE_COUNT
      const qx = (cx + r * BOW * Math.cos(midA)).toFixed(1)
      const qy = (cy + r * BOW * Math.sin(midA)).toFixed(1)
      const next = (i + 1) % SPOKE_COUNT
      parts.push(`Q ${qx} ${qy} ${pts[next].x.toFixed(1)} ${pts[next].y.toFixed(1)}`)
    }
    return { d: parts.join(' '), delay: 350 + j * 150 }
  })

  const stroke = mode === 'bw' ? 'rgba(255,255,255,0.80)' : 'rgba(233,178,102,0.90)'

  return createPortal(
    <svg
      aria-hidden="true"
      style={{
        position: 'fixed',
        inset: 0,
        width: '100vw',
        height: '100vh',
        pointerEvents: 'none',
        zIndex: 99999,
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
      {rings.map((ring, j) => (
        <path
          key={j}
          d={ring.d}
          fill="none"
          stroke={stroke}
          strokeWidth="0.8"
          pathLength={1000}
          style={
            {
              strokeDasharray: 1000,
              strokeDashoffset: 1000,
              '--dash-len': '1000',
              animationName: 'spider-silk-draw',
              animationDuration: '350ms',
              animationDelay: `${ring.delay}ms`,
              animationTimingFunction: 'ease-out',
              animationFillMode: 'forwards',
            } as React.CSSProperties
          }
        />
      ))}
    </svg>,
    document.body
  )
}
