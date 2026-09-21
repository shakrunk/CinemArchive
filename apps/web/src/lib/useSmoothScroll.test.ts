import { createElement } from 'react'
import { cleanup, render, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WatchProviderListings } from '../components/ui/watch-providers'
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

function wheel(target: HTMLElement, deltaY = 100, deltaX = 0) {
  const event = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY, deltaX })
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

  it.each([0, 400, 800])('keeps vertical scrolling over a horizontal row at offset %i', (scrollLeft) => {
    const wrapper = mount()
    const { getByTitle } = render(createElement(WatchProviderListings, {
      providers: {
        flatrate: [{ providerId: 1, name: 'Test provider', logoUrl: '' }],
        free: [], ads: [], rent: [], buy: [], link: '',
      },
    }), { container: wrapper })
    const child = getByTitle('Test provider')
    const area = child.parentElement!
    // jsdom has no layout or Tailwind styles; supply the row's browser dimensions.
    Object.assign(area.style, {
      overflowX: 'auto', overflowY: 'auto',
      overscrollBehaviorX: 'auto', overscrollBehaviorY: 'auto',
    })
    Object.defineProperties(area, {
      clientWidth: { value: 200 }, scrollWidth: { value: 1000 },
      clientHeight: { value: 40 }, scrollHeight: { value: 40 },
    })
    area.scrollLeft = scrollLeft

    expect(wheel(wrapper)).toBe(true)
    expect(wheel(child)).toBe(true)
    expect(wheel(child, -100)).toBe(true)
    expect(wheel(child, 100, 5)).toBe(true)
    expect(wheel(child, 0, 100)).toBe(false)
    expect(wheel(child, 0, -100)).toBe(false)
    if (scrollLeft < 800) expect(wheel(child, 5, 100)).toBe(false)
    if (scrollLeft > 0) expect(wheel(child, -5, -100)).toBe(false)
  })
})
