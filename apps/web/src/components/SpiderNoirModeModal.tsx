import { ChoiceModal, ChoiceCard } from 'src/components/ui/choice-modal'

interface SpiderNoirModeModalProps {
  open: boolean
  onSelect: (mode: 'bw' | 'color') => void
  onSkip: () => void
}

export function SpiderNoirModeModal({ open, onSelect, onSkip }: SpiderNoirModeModalProps) {
  return (
    <ChoiceModal
      open={open}
      onDismiss={onSkip}
      ariaLabel="How did you experience this?"
      backdropColor="rgba(11, 9, 7, 0.92)"
    >
      <h2
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
      </h2>

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

      <div style={{ display: 'flex', gap: '16px', width: '100%', maxWidth: '480px' }}>
        <ChoiceCard
          ariaLabel="Authentic Black & White"
          onClick={() => onSelect('bw')}
          borderColor="rgba(255,255,255,0.15)"
          hoverBorderColor="rgba(255,255,255,0.4)"
          background="linear-gradient(160deg, rgba(80,80,80,0.3), rgba(20,20,20,0.5))"
          filter="grayscale(1)"
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
              Black &amp; White
            </div>
          </div>
        </ChoiceCard>

        <ChoiceCard
          ariaLabel="True-Hue Full Color"
          onClick={() => onSelect('color')}
          borderColor="rgba(233, 178, 102, 0.3)"
          hoverBorderColor="rgba(233, 178, 102, 0.7)"
          hoverShadowColor="rgba(233, 178, 102, 0.4)"
          background="linear-gradient(160deg, rgba(192, 57, 43, 0.35), rgba(233, 178, 102, 0.2))"
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
        </ChoiceCard>
      </div>

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
    </ChoiceModal>
  )
}
