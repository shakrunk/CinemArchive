import { useEffect } from 'react'
import { createPortal } from 'react-dom'

interface MatrixPillModalProps {
  open: boolean
  onBlue: () => void
  onRed: () => void
}

function PillIcon({ color }: { color: 'blue' | 'red' }) {
  return (
    <div
      aria-hidden="true"
      style={{
        width: '48px',
        height: '22px',
        borderRadius: '11px',
        background:
          color === 'blue'
            ? 'linear-gradient(135deg, #4a9eff, #1a5fbf)'
            : 'linear-gradient(135deg, #ff4444, #991111)',
        boxShadow:
          color === 'blue'
            ? '0 0 12px rgba(74, 158, 255, 0.4), inset 0 1px 1px rgba(255,255,255,0.2)'
            : '0 0 12px rgba(255, 68, 68, 0.4), inset 0 1px 1px rgba(255,255,255,0.2)',
      }}
    />
  )
}

export function MatrixPillModal({ open, onBlue, onRed }: MatrixPillModalProps) {
  useEffect(() => {
    if (!open) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onBlue()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onBlue])

  if (!open) return null

  return createPortal(
    <>
      <div
        aria-hidden="true"
        onClick={onBlue}
        style={{
          position: 'fixed',
          inset: 0,
          zIndex: 10000,
          background: 'rgba(0, 5, 0, 0.96)',
          backdropFilter: 'blur(8px)',
          animation: 'spider-noir-fade-in 300ms ease',
        }}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Choose your pill"
        style={{
          position: 'fixed',
          inset: 0,
          zIndex: 10001,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '24px',
          pointerEvents: 'none',
        }}
      >
        <div style={{ display: 'contents', pointerEvents: 'auto' }}>
          <h2
            style={{
              fontFamily: 'var(--serif)',
              fontSize: 'clamp(20px, 5vw, 28px)',
              color: '#00ff41',
              marginBottom: '8px',
              textAlign: 'center',
              letterSpacing: '-0.01em',
              textShadow: '0 0 24px rgba(0, 255, 65, 0.45)',
            }}
          >
            This is your last chance.
          </h2>

          <p
            style={{
              fontFamily: 'var(--mono)',
              fontSize: '11px',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              color: 'rgba(0, 255, 65, 0.45)',
              marginBottom: '32px',
              textAlign: 'center',
            }}
          >
            The Matrix · 1999
          </p>

          <div style={{ display: 'flex', gap: '16px', width: '100%', maxWidth: '480px' }}>
            {/* Blue Pill */}
            <button
              aria-label="Take the Blue Pill — continue as normal"
              onClick={onBlue}
              style={{
                flex: 1,
                border: '1px solid rgba(74, 158, 255, 0.25)',
                borderRadius: '12px',
                padding: '28px 16px',
                cursor: 'pointer',
                transition: 'transform 0.18s, border-color 0.18s, box-shadow 0.18s',
                background: 'linear-gradient(160deg, rgba(30, 80, 180, 0.25), rgba(10, 30, 80, 0.4))',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '14px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-3px)'
                e.currentTarget.style.borderColor = 'rgba(74, 158, 255, 0.6)'
                e.currentTarget.style.boxShadow = '0 8px 32px -8px rgba(74, 158, 255, 0.35)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = ''
                e.currentTarget.style.borderColor = 'rgba(74, 158, 255, 0.25)'
                e.currentTarget.style.boxShadow = ''
              }}
            >
              <PillIcon color="blue" />
              <div style={{ textAlign: 'center' }}>
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontSize: '15px',
                    color: '#7fb3f5',
                    marginBottom: '5px',
                    fontVariationSettings: '"opsz" 24',
                  }}
                >
                  Blue Pill
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: '10px',
                    color: 'rgba(127, 179, 245, 0.55)',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}
                >
                  The story ends
                </div>
              </div>
            </button>

            {/* Red Pill */}
            <button
              aria-label="Take the Red Pill — see how deep the rabbit hole goes"
              onClick={onRed}
              style={{
                flex: 1,
                border: '1px solid rgba(220, 50, 50, 0.25)',
                borderRadius: '12px',
                padding: '28px 16px',
                cursor: 'pointer',
                transition: 'transform 0.18s, border-color 0.18s, box-shadow 0.18s',
                background: 'linear-gradient(160deg, rgba(160, 20, 20, 0.28), rgba(60, 5, 5, 0.4))',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '14px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-3px)'
                e.currentTarget.style.borderColor = 'rgba(220, 50, 50, 0.65)'
                e.currentTarget.style.boxShadow = '0 8px 32px -8px rgba(220, 50, 50, 0.45)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = ''
                e.currentTarget.style.borderColor = 'rgba(220, 50, 50, 0.25)'
                e.currentTarget.style.boxShadow = ''
              }}
            >
              <PillIcon color="red" />
              <div style={{ textAlign: 'center' }}>
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontSize: '15px',
                    color: '#f87171',
                    marginBottom: '5px',
                    fontVariationSettings: '"opsz" 24',
                  }}
                >
                  Red Pill
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: '10px',
                    color: 'rgba(248, 113, 113, 0.55)',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}
                >
                  See how deep
                </div>
              </div>
            </button>
          </div>

        </div>
      </div>
    </>,
    document.body,
  )
}
