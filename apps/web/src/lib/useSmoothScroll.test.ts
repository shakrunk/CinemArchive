import { cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useScopedSmoothScroll, useSmoothScroll } from './useSmoothScroll'

// Keep Lenis real: assert who owns a wheel event, not its constructor options.
beforeEach(() => {
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })))
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  })
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})

afterEach(() => {
  cleanup()
  document.body.replaceChildren()
  vi.unstubAllGlobals()
})

function scrollArea(parent: HTMLElement, overflowing = true) {
  const area = document.createElement('div')
  area.style.overflowY = 'auto'
  area.style.overscrollBehaviorY = 'auto'
  Object.defineProperties(area, {
    clientHeight: { value: 200 },
    scrollHeight: { value: overflowing ? 1000 : 200 },
  })
  const child = document.createElement('button')
  area.append(child)
  parent.append(area)
  return { area, child }
}

function wheel(target: HTMLElement, deltaY = 100) {
  const event = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY })
  target.dispatchEvent(event)
  return event.defaultPrevented
}

describe.each(['page', 'drawer'] as const)('%s smooth scrolling', (scope) => {
  function mount() {
    const wrapper = document.createElement('div')
    document.body.append(wrapper)
    renderHook(() => {
      useSmoothScroll()
      useScopedSmoothScroll(scope === 'drawer' ? wrapper : null, scope === 'drawer')
    })
    return wrapper
  }

  it('leaves wheel input native inside an overflowing panel', () => {
    const { child } = scrollArea(mount())
    expect(wheel(child)).toBe(false)
  })

  it('still smooths wheel input on ordinary content', () => {
    expect(wheel(mount())).toBe(true)
  })

  it('does not trap wheel input in a panel without overflow', () => {
    const { child } = scrollArea(mount(), false)
    expect(wheel(child)).toBe(true)
  })

  it('chains to the outer scroller at the panel boundary', () => {
    const { area, child } = scrollArea(mount())
    area.scrollTop = 800
    expect(wheel(child)).toBe(true)
    expect(wheel(child, -100)).toBe(false)
  })
})
