import { scaleTextLength } from '../src/lib/textScale.js'

const SCALE = 'var(--text-scale, 1)'
const THEME_SIZE = /^var\(--text-(?!scale\b)[\w-]+\)$/

/** Runs on Tailwind's output so arbitrary text-[Npx], responsive variants and @apply all participate.
 * Tailwind v4 emits the type scale as `font-size: var(--text-sm)` and derives leading-N from --spacing,
 * so those are wrapped here. Wrapping at the declaration (not in the theme variable) lets every element
 * follow the nearest --text-scale, including the staged preview that sets it on a subtree.
 * Only typography changes: root size, widths, spacing, icons and hit targets keep their units. */
export default function textScale() {
  return {
    postcssPlugin: 'cinemarchive-text-scale',
    OnceExit(root) {
      root.walkDecls(/^(font-size|line-height|--tw-leading)$/, (declaration) => {
        const { value } = declaration
        if (value.includes('--text-scale')) return
        declaration.value = THEME_SIZE.test(value) || value.includes('var(--spacing)')
          ? `calc(${value} * ${SCALE})`
          : scaleTextLength(value)
      })
    },
  }
}
