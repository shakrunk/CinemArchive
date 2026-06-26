import * as DialogPrimitive from '@radix-ui/react-dialog'

interface SpiderNoirModeModalProps {
  open: boolean
  onSelect: (mode: 'bw' | 'color') => void
  onSkip: () => void
}

export function SpiderNoirModeModal({ open, onSelect, onSkip }: SpiderNoirModeModalProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(o) => { if (!o) onSkip() }}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 10000,
            background: 'rgba(11, 9, 7, 0.92)',
            backdropFilter: 'blur(8px)',
            animation: 'spider-noir-fade-in 300ms ease',
          }}
        />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 10001,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '24px',
          }}
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <DialogPrimitive.Title
            style={{
              fontFamily: 'var(--serif)',
              fontSize: 'clamp(20px, 5vw, 28px)',
              color: 'rgb(var(--ivory))',
              marginBottom: '8px',
              textAlign: 'center',
              letterSpacing: '-0.01em',
            }}
          >
            How did you experience this?
          </DialogPrimitive.Title>

          <p
            style={{
              fontFamily: 'var(--mono)',
              fontSize: '11px',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              color: 'rgba(243, 234, 217, 0.5)',
              marginBottom: '32px',
              textAlign: 'center',
            }}
          >
            Spider-Man: Noir
          </p>

          {/* Choice cards */}
          <div style={{ display: 'flex', gap: '16px', width: '100%', maxWidth: '480px' }}>
            {/* B&W card */}
            <button
              aria-label="Authentic Black & White"
              onClick={() => onSelect('bw')}
              style={{
                flex: 1,
                border: '1px solid rgba(255,255,255,0.15)',
                borderRadius: '12px',
                padding: '24px 16px',
                cursor: 'pointer',
                transition: 'transform 0.18s, border-color 0.18s',
                filter: 'grayscale(1)',
                background: 'linear-gradient(160deg, rgba(80,80,80,0.3), rgba(20,20,20,0.5))',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '12px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-3px)'
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.4)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = ''
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.15)'
              }}
            >
              <span style={{ fontSize: '36px', lineHeight: 1, color: '#ccc' }}>◐</span>
              <div style={{ textAlign: 'center' }}>
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontSize: '15px',
                    color: '#e0e0e0',
                    marginBottom: '4px',
                    fontVariationSettings: '"opsz" 24',
                  }}
                >
                  Authentic
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: '10px',
                    color: '#999',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}
                >
                  Black & White
                </div>
              </div>
            </button>

            {/* Color card */}
            <button
              aria-label="True-Hue Full Color"
              onClick={() => onSelect('color')}
              style={{
                flex: 1,
                border: '1px solid rgba(233, 178, 102, 0.3)',
                borderRadius: '12px',
                padding: '24px 16px',
                cursor: 'pointer',
                transition: 'transform 0.18s, border-color 0.18s, box-shadow 0.18s',
                background: 'linear-gradient(160deg, rgba(192, 57, 43, 0.35), rgba(233, 178, 102, 0.2))',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '12px',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-3px)'
                e.currentTarget.style.borderColor = 'rgba(233, 178, 102, 0.7)'
                e.currentTarget.style.boxShadow = '0 8px 32px -8px rgba(233, 178, 102, 0.4)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = ''
                e.currentTarget.style.borderColor = 'rgba(233, 178, 102, 0.3)'
                e.currentTarget.style.boxShadow = ''
              }}
            >
              <span style={{ fontSize: '36px', lineHeight: 1, color: 'var(--amber)' }}>◈</span>
              <div style={{ textAlign: 'center' }}>
                <div
                  style={{
                    fontFamily: 'var(--serif)',
                    fontSize: '15px',
                    color: 'var(--amber)',
                    marginBottom: '4px',
                    fontVariationSettings: '"opsz" 24',
                  }}
                >
                  True-Hue
                </div>
                <div
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: '10px',
                    color: 'var(--amber-deep)',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                  }}
                >
                  Full Color
                </div>
              </div>
            </button>
          </div>

          {/* Skip */}
          <button
            onClick={onSkip}
            style={{
              marginTop: '24px',
              fontFamily: 'var(--mono)',
              fontSize: '11px',
              color: 'rgba(243, 234, 217, 0.5)',
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              letterSpacing: '0.08em',
              transition: 'color 0.15s',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.color = 'rgba(243, 234, 217, 0.7)')}
            onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(243, 234, 217, 0.5)')}
          >
            not now
          </button>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
