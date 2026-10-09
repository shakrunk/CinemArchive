import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { TextPreferences } from './TextPreferences'
import { DEFAULT_TEXT_PREFERENCES, TEXT_PREFERENCES_KEY, saveTextPreferences, watchTextPreferences } from 'src/lib/textPreferences'

beforeEach(() => { saveTextPreferences(DEFAULT_TEXT_PREFERENCES) })
afterEach(() => { cleanup(); vi.restoreAllMocks(); saveTextPreferences(DEFAULT_TEXT_PREFERENCES) })

describe('text preference preview', () => {
  it('scopes a draft to the preview and cancels without persisting', () => {
    render(<TextPreferences />)
    fireEvent.click(screen.getByLabelText('Lexend · dyslexia-friendly'))
    fireEvent.click(screen.getByLabelText('Extra large'))
    expect(screen.getByLabelText('Text preview').dataset.textFamily).toBe('lexend')
    expect(screen.getByLabelText('Text preview').style.getPropertyValue('--text-scale')).toBe('1.3')
    expect(document.documentElement.dataset.textFamily).toBe('default')
    expect(document.documentElement.style.getPropertyValue('--text-scale')).toBe('1')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel preview' }))
    expect(screen.getByLabelText('Default · cinematic')).toBeChecked()
    expect(screen.getByLabelText('Default')).toBeChecked()
    expect(JSON.parse(localStorage.getItem(TEXT_PREFERENCES_KEY)!)).toEqual(DEFAULT_TEXT_PREFERENCES)
  })

  it('applies in memory with an honest warning when persistence is blocked', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Denied', 'SecurityError') })
    render(<TextPreferences />)
    fireEvent.click(screen.getByLabelText('Large'))
    fireEvent.click(screen.getByRole('button', { name: 'Apply text preferences' }))
    expect(document.documentElement.style.getPropertyValue('--text-scale')).toBe('1.15')
    expect(screen.getByRole('status')).toHaveTextContent('Applied for this visit')
    expect(screen.getByRole('button', { name: 'Apply text preferences' })).toBeDisabled()
  })

  it('discards stale drafts on another tab update and stages reset until Apply', () => {
    const stop = watchTextPreferences()
    render(<TextPreferences />)
    fireEvent.click(screen.getByLabelText('Small'))
    act(() => window.dispatchEvent(new StorageEvent('storage', { key: TEXT_PREFERENCES_KEY, newValue: JSON.stringify({ family: 'lexend', size: 'extra-large' }) })))
    expect(screen.getByLabelText('Extra large')).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Reset text defaults' }))
    expect(document.documentElement.dataset.textFamily).toBe('lexend')
    fireEvent.click(screen.getByRole('button', { name: 'Apply text preferences' }))
    expect(document.documentElement.dataset.textFamily).toBe('default')
    expect(screen.getByRole('status')).toHaveTextContent('Text preferences applied.')
    stop()
  })
})
