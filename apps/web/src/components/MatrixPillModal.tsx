import { ChoiceModal, ChoiceCard } from 'src/components/ui/choice-modal'

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
  return (
    <ChoiceModal open={open} onDismiss={onBlue} ariaLabel="Choose your pill" backdropColor="rgba(0, 5, 0, 0.96)">
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
        <ChoiceCard
          ariaLabel="Take the Blue Pill — continue as normal"
          onClick={onBlue}
          borderColor="rgba(74, 158, 255, 0.25)"
          hoverBorderColor="rgba(74, 158, 255, 0.6)"
          hoverShadowColor="rgba(74, 158, 255, 0.35)"
          background="linear-gradient(160deg, rgba(30, 80, 180, 0.25), rgba(10, 30, 80, 0.4))"
          gap="14px"
          padding="28px 16px"
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
        </ChoiceCard>

        <ChoiceCard
          ariaLabel="Take the Red Pill — see how deep the rabbit hole goes"
          onClick={onRed}
          borderColor="rgba(220, 50, 50, 0.25)"
          hoverBorderColor="rgba(220, 50, 50, 0.65)"
          hoverShadowColor="rgba(220, 50, 50, 0.45)"
          background="linear-gradient(160deg, rgba(160, 20, 20, 0.28), rgba(60, 5, 5, 0.4))"
          gap="14px"
          padding="28px 16px"
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
        </ChoiceCard>
      </div>
    </ChoiceModal>
  )
}
