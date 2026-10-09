import { describe, expect, it } from 'vitest'
import postcss from 'postcss'
import { compile } from '@tailwindcss/node'
import textScale from '../../scripts/postcss-text-scale.js'
import { scaledTextSize, scaleTextLength } from './textScale.js'

describe('text scaling', () => {
  it('scales absolute text lengths once while respecting inherited and relative sizes', () => {
    expect(scaledTextSize(12)).toBe('calc(0.75rem * var(--text-scale, 1))')
    expect(scaleTextLength('clamp(12px, calc(1rem + 2vw), 3rem)')).toBe('clamp(calc(0.75rem * var(--text-scale, 1)), calc(calc(1rem * var(--text-scale, 1)) + calc(2vw * var(--text-scale, 1))), calc(3rem * var(--text-scale, 1)))')
    for (const value of ['1.5', '1.2em', '120%', 'inherit', 'var(--custom-size)']) expect(scaleTextLength(value)).toBe(value)
    const once = scaleTextLength('11px')
    expect(scaleTextLength(once)).toBe(once)
  })

  it('covers generated Tailwind, arbitrary px, @apply and keyframes without scaling layout', async () => {
    const compiler = await compile(
      '@import "tailwindcss/theme" layer(theme); @import "tailwindcss/utilities" layer(utilities); .label { @apply text-sm; } @keyframes grow { to { font-size: 24px; line-height: 30px; width: 24px; } }',
      { base: import.meta.dirname, onDependency: () => {} },
    )
    const css = compiler.build(['text-sm', 'text-[11px]', 'leading-6', 'w-6'])
    const result = await postcss([textScale()]).process(css, { from: undefined })
    const declarations = (selector, property) => {
      const values = []
      result.root.walkRules(selector, rule => rule.walkDecls(property, decl => values.push(decl.value)))
      return values
    }
    // v4 resolves text-sm / @apply text-sm through the theme variable, so the variable carries the scale.
    const themeVar = []
    result.root.walkDecls('--text-sm', decl => themeVar.push(decl.value))
    expect(themeVar).toEqual(['calc(0.875rem * var(--text-scale, 1))'])
    expect(declarations('.text-sm', 'font-size')).toEqual(['var(--text-sm)'])
    expect(declarations('.label', 'font-size')).toEqual(['var(--text-sm)'])
    expect(declarations('.text-\\[11px\\]', 'font-size')).toEqual(['calc(0.6875rem * var(--text-scale, 1))'])
    expect(declarations('.leading-6', 'line-height')).toEqual(['calc(calc(var(--spacing) * 6) * var(--text-scale, 1))'])
    expect(declarations('.w-6', 'width')).toEqual(['calc(var(--spacing) * 6)'])
    expect(declarations('to', 'font-size')).toEqual(['calc(1.5rem * var(--text-scale, 1))'])
    expect(declarations('to', 'width')).toEqual(['24px'])
  })
})
