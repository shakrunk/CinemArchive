import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { CommandPalette } from './CommandPalette'
import { AppCommandPalette } from './AppCommandPalette'
import { useAppStore } from 'src/store/useAppStore'
import type { Command } from 'src/store/commands'

const commands: Command[] = [
  { id: 'first', kind: 'title', label: 'First film' },
  { id: 'second', kind: 'title', label: 'Second film' },
]

beforeEach(() => { Element.prototype.scrollIntoView = vi.fn() })
afterEach(cleanup)

it('keeps Enter working when library updates remove the highlighted result', () => {
  const onRun = vi.fn()
  const props = { open: true, onClose: vi.fn(), onRun }
  const { rerender } = render(<CommandPalette {...props} commands={commands} />)
  const input = screen.getByRole('combobox')
  fireEvent.keyDown(input, { key: 'ArrowDown' })
  rerender(<CommandPalette {...props} commands={[commands[0]]} />)
  fireEvent.keyDown(input, { key: 'Enter' })
  expect(onRun).toHaveBeenCalledExactlyOnceWith(commands[0])
})

it('activates the result focused with Tab instead of an unrelated highlighted result', () => {
  const onRun = vi.fn()
  render(<CommandPalette open onClose={vi.fn()} onRun={onRun} commands={commands} />)
  const option = screen.getByRole('option', { name: /Second film/ })
  fireEvent.focus(option)
  fireEvent.keyDown(option, { key: 'Enter' })
  expect(onRun).toHaveBeenCalledExactlyOnceWith(commands[1])
})

it('keeps the chosen title selected when results reorder', () => {
  const onRun = vi.fn()
  const props = { open: true, onClose: vi.fn(), onRun }
  const { rerender } = render(<CommandPalette {...props} commands={commands} />)
  const input = screen.getByRole('combobox')
  fireEvent.keyDown(input, { key: 'ArrowDown' })
  rerender(<CommandPalette {...props} commands={[commands[1], commands[0]]} />)
  fireEvent.keyDown(input, { key: 'Enter' })
  expect(onRun).toHaveBeenCalledExactlyOnceWith(commands[1])
})

it('closes the palette and opens the matching title when Enter is pressed', () => {
  useAppStore.setState({
    isCommandPaletteOpen: true, isDetailDrawerOpen: false, selectedTitleId: null,
    titles: [{ id: 'film-42', tmdbId: 42, type: 'movie', title: 'A new discovery', year: 2026, genres: [], tags: [], viewings: [], status: 'watched', addedAt: '2026-09-19' }],
  })
  render(<AppCommandPalette onNavigate={vi.fn()} />)
  const input = screen.getByRole('combobox')
  fireEvent.change(input, { target: { value: 'new discovery' } })
  fireEvent.keyDown(input, { key: 'Enter' })
  expect(useAppStore.getState()).toMatchObject({
    isCommandPaletteOpen: false, isDetailDrawerOpen: true, selectedTitleId: 'film-42',
  })
})
