interface ReelMarkProps {
  className?: string
}

/**
 * 3-spoke cinematic film-reel mark.
 * Spokes at 0°/120°/240°; sprocket holes at 60°/180°/300°.
 * Inherits color via `currentColor` — size via className.
 */
export function ReelMark({ className }: ReelMarkProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      {/* Outer ring */}
      <circle cx="12" cy="12" r="10" />
      {/* Inner hub ring */}
      <circle cx="12" cy="12" r="4.5" />
      {/* 3 spokes: top (0°), lower-right (120°), lower-left (240°) */}
      <line x1="12" y1="7.5" x2="12" y2="2" />
      <line x1="15.9" y1="14.25" x2="20.66" y2="17" />
      <line x1="8.1" y1="14.25" x2="3.34" y2="17" />
      {/* 3 sprocket holes: between spokes at 60°, 180°, 300°, r=7.5 */}
      <circle cx="18.5" cy="8.25" r="1.4" />
      <circle cx="12" cy="19.5" r="1.4" />
      <circle cx="5.5" cy="8.25" r="1.4" />
      {/* Center axle */}
      <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" />
    </svg>
  )
}
