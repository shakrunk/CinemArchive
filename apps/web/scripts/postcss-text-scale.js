import { scaleTextLength } from '../src/lib/textScale.js'

const SCALE = 'var(--text-scale, 1)'

/** Runs on Tailwind's output so arbitrary text-[Npx], responsive variants and @apply all participate.
 * Tailwind v4 keeps the type scale in theme variables (--text-sm: .875rem) and derives leading-N from
 * --spacing, so those are scaled here too; plain `var(--text-sm)` consumers then inherit the scale.
 * Only typography changes: root size, widths, spacing, icons and hit targets keep their units. */
export default function textScale() {
  return {
    postcssPlugin: 'cinemarchive-text-scale',
    OnceExit(root) {
      root.walkDecls(/^(font-size|line-height|--tw-leading|--text-[\w-]+)$/, (declaration) => {
        const { value } = declaration
        declaration.value = value.includes('var(--spacing)') && !value.includes('--text-scale')
          ? `calc(${value} * ${SCALE})`
          : scaleTextLength(value)
      })
    },
  }
}
