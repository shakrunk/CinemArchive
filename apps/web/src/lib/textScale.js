/** Scale absolute text lengths once, preserving inherited em/% and the browser's root size.
 * Shared by the CSS build and the few components with inline/dynamic font sizes. */
export function scaleTextLength(value) {
  if (value.includes('--text-scale')) return value
  return value.replace(/(?<![\w.-])((?:\d*\.)?\d+)(px|rem|vw|vh|vmin|vmax)\b/g, (_, amount, unit) => {
    const length = unit === 'px' ? `${Number(amount) / 16}rem` : `${amount}${unit}`
    return `calc(${length} * var(--text-scale, 1))`
  })
}

/** React numeric fontSize values are pixels, just like explicit px strings. */
export function scaledTextSize(value) {
  return scaleTextLength(typeof value === 'number' ? `${value}px` : value)
}
