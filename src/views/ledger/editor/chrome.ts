// Shared visual bits for the layout editor's floating panels.

export const editorBtnClass =
  'inline-flex items-center justify-center gap-1.5 rounded-md border border-[var(--line)] px-2.5 py-1.5 text-xs font-sans text-paper-dim hover:text-paper hover:border-[var(--line-2)] transition-colors disabled:opacity-35 disabled:pointer-events-none'

export const floatingPanelStyle: React.CSSProperties = {
  background: 'linear-gradient(168deg, var(--ink-1), var(--ink-2))',
  boxShadow: '0 24px 60px -18px rgba(0,0,0,0.75)',
}
