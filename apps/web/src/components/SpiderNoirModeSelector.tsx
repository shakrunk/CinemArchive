import { Pin } from 'lucide-react'
import { cn } from 'src/lib/utils'

type ColorMode = 'bw' | 'color'
type SelectorMode = 'normal' | ColorMode

interface SpiderNoirModeSelectorProps {
  unlockedModes: Set<ColorMode>
  earnedModes: Set<ColorMode>
  selected: SelectorMode
  pinned: ColorMode | null
  onSelect: (mode: SelectorMode) => void
  onTogglePin: (mode: ColorMode) => void
}

const SEGMENTS: Array<{ mode: SelectorMode; icon: string; label: string }> = [
  { mode: 'normal', icon: '○', label: 'Normal' },
  { mode: 'bw',     icon: '◐', label: 'B&W'    },
  { mode: 'color',  icon: '◈', label: 'Color'  },
]

export function SpiderNoirModeSelector({
  unlockedModes,
  earnedModes,
  selected,
  pinned,
  onSelect,
  onTogglePin,
}: SpiderNoirModeSelectorProps) {
  if (unlockedModes.size === 0) return null

  const visible = SEGMENTS.filter(
    (s) => s.mode === 'normal' || unlockedModes.has(s.mode as ColorMode)
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
      <div style={{ display: 'flex', gap: '5px', flexWrap: 'wrap' }}>
        {visible.map(({ mode, icon, label }) => {
          const isActive   = selected === mode
          const isColor    = mode !== 'normal'
          const isEarned   = isColor && earnedModes.has(mode as ColorMode)
          const isPinned   = isColor && pinned === (mode as ColorMode)

          return (
            <div key={mode} style={{ display: 'flex', alignItems: 'center', gap: '2px' }}>
              <button
                type="button"
                onClick={() => onSelect(mode)}
                aria-pressed={isActive}
                className={cn(
                  'flex items-center gap-1.5 rounded-full font-mono transition-all',
                  isActive
                    ? 'bg-amber/15 text-amber'
                    : 'bg-transparent hover:text-paper'
                )}
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  border: `1px solid ${isActive ? 'rgba(233,178,102,0.50)' : 'var(--line)'}`,
                  color: isActive ? 'var(--amber)' : 'var(--paper-faint)',
                  letterSpacing: '0.04em',
                }}
              >
                <span>{icon}</span>
                <span>{label}</span>
              </button>

              {/* Pin button — only when this non-normal segment is active and earned */}
              {isColor && isActive && isEarned && (
                <button
                  type="button"
                  onClick={() => onTogglePin(mode as ColorMode)}
                  aria-label={isPinned ? `Unpin ${label} mode` : `Pin ${label} mode`}
                  title={isPinned ? 'Unpin — filter will reset when you leave' : 'Pin — filter stays on when you leave'}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: '22px',
                    height: '22px',
                    borderRadius: '50%',
                    border: `1px solid ${isPinned ? 'rgba(233,178,102,0.50)' : 'var(--line)'}`,
                    background: isPinned ? 'rgba(233,178,102,0.12)' : 'transparent',
                    color: isPinned ? 'var(--amber)' : 'var(--paper-faint)',
                    transition: 'color 0.15s, background 0.15s, border-color 0.15s',
                    cursor: 'pointer',
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.color = 'var(--amber)'
                    e.currentTarget.style.borderColor = 'rgba(233,178,102,0.5)'
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.color = isPinned ? 'var(--amber)' : 'var(--paper-faint)'
                    e.currentTarget.style.borderColor = isPinned ? 'rgba(233,178,102,0.5)' : 'var(--line)'
                  }}
                >
                  <Pin
                    className="w-3 h-3"
                    style={{ transform: isPinned ? 'none' : 'rotate(45deg)', transition: 'transform 0.2s' }}
                  />
                </button>
              )}
            </div>
          )
        })}
      </div>

      {pinned && (
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: '10px',
            color: 'var(--paper-faint)',
            letterSpacing: '0.06em',
          }}
        >
          filter stays on when you leave
        </div>
      )}
    </div>
  )
}

export default SpiderNoirModeSelector
