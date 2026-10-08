import { scaleTextLength } from '../src/lib/textScale.js'

/** Runs after Tailwind so arbitrary text-[Npx], responsive variants and @apply all participate.
 * Only typography changes: root size, widths, spacing, icons and hit targets keep their units. */
export default function textScale() {
  return {
    postcssPlugin: 'cinemarchive-text-scale',
    OnceExit(root) {
      root.walkDecls(/^(font-size|line-height)$/, (declaration) => {
        declaration.value = scaleTextLength(declaration.value)
      })
    },
  }
}
