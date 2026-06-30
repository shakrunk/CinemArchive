interface MatrixPillSelectorProps {
  onRedPill: () => void
}

function PillDot({ color }: { color: 'blue' | 'red' }) {
  return (
    <span
      aria-hidden="true"
      style={{
        display: 'inline-block',
        width: '8px',
        height: '8px',
        borderRadius: '50%',
        background: color === 'blue' ? '#4a9eff' : '#ff4444',
        boxShadow: color === 'blue' ? '0 0 4px rgba(74,158,255,0.6)' : '0 0 4px rgba(255,68,68,0.6)',
      }}
    />
  )
}

export function MatrixPillSelector({ onRedPill }: MatrixPillSelectorProps) {
  return (
    <div style={{ display: 'flex', gap: '5px', flexWrap: 'wrap' }}>
      {/* Blue pill — clickable but no-op */}
      <button
        type="button"
        aria-label="Blue Pill — the story ends"
        title="Blue Pill — the story ends"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          borderRadius: '9999px',
          padding: '4px 10px',
          fontSize: '11px',
          fontFamily: 'var(--mono)',
          letterSpacing: '0.04em',
          background: 'rgba(74, 158, 255, 0.08)',
          border: '1px solid rgba(74, 158, 255, 0.3)',
          color: '#7fb3f5',
          cursor: 'pointer',
          transition: 'background 0.15s, border-color 0.15s',
        }}
        onMouseEnter={(e) => {
          e.currentTarget.style.background = 'rgba(74, 158, 255, 0.18)'
          e.currentTarget.style.borderColor = 'rgba(74, 158, 255, 0.55)'
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.background = 'rgba(74, 158, 255, 0.08)'
          e.currentTarget.style.borderColor = 'rgba(74, 158, 255, 0.3)'
        }}
      >
        <PillDot color="blue" />
        <span>Blue Pill</span>
      </button>

      {/* Red pill — clickable, replays the rain */}
      <button
        type="button"
        onClick={onRedPill}
        aria-label="Red Pill — replay the digital rain"
        title="Red Pill — replay the digital rain"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          borderRadius: '9999px',
          padding: '4px 10px',
          fontSize: '11px',
          fontFamily: 'var(--mono)',
          letterSpacing: '0.04em',
          background: 'rgba(220, 50, 50, 0.08)',
          border: '1px solid rgba(220, 50, 50, 0.3)',
          color: '#f87171',
          cursor: 'pointer',
          transition: 'background 0.15s, border-color 0.15s',
        }}
        onMouseEnter={(e) => {
          e.currentTarget.style.background = 'rgba(220, 50, 50, 0.18)'
          e.currentTarget.style.borderColor = 'rgba(220, 50, 50, 0.55)'
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.background = 'rgba(220, 50, 50, 0.08)'
          e.currentTarget.style.borderColor = 'rgba(220, 50, 50, 0.3)'
        }}
      >
        <PillDot color="red" />
        <span>Red Pill</span>
      </button>
    </div>
  )
}

export default MatrixPillSelector
