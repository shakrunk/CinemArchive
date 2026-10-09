import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { PointerEvent } from 'react'
import { WidgetDetails } from './WidgetDetails'
import { useBoardDrag } from './useBoardDrag'

const fixture = vi.hoisted(() => ({
  state: {
    ledgerPrefs: { widgets: [{ id: 'one', panel: 'genres', width: 'md', settings: { title: 'Original' } }] },
    user: { id: 'owner' }, viewerContext: { kind: 'owner' }, isSharedView: false,
    duplicateLedgerWidget: vi.fn(), removeLedgerWidget: vi.fn(), moveLedgerWidget: vi.fn(),
    setLedgerWidgetWidth: vi.fn(), setLedgerWidgetSettings: vi.fn(),
    reorderLedgerWidgets: vi.fn(), addLedgerWidget: vi.fn(),
  },
}))
vi.mock('src/store/useAppStore', () => ({
  useAppStore: Object.assign((select: (state: typeof fixture.state) => unknown) => select(fixture.state), {
    getState: () => fixture.state,
  }),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
beforeEach(() => {
  vi.resetAllMocks()
  fixture.state.ledgerPrefs.widgets = [{ id: 'one', panel: 'genres', width: 'md', settings: { title: 'Original' } }]
})
afterEach(cleanup)

it('selects a duplicate only after local persistence resolves', async () => {
  const pending = deferred<string>()
  fixture.state.duplicateLedgerWidget.mockReturnValue(pending.promise)
  const onSelect = vi.fn()
  render(<WidgetDetails selectedId="one" onSelect={onSelect} onHide={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
  expect(onSelect).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Remove from board' })).toBeDisabled()
  await act(async () => pending.resolve('new-widget'))
  expect(onSelect).toHaveBeenCalledWith('new-widget')
})

it('keeps selection and permits retry when removing a widget fails', async () => {
  fixture.state.removeLedgerWidget.mockRejectedValueOnce(new Error('Device storage is full')).mockResolvedValueOnce(undefined)
  const onSelect = vi.fn()
  render(<WidgetDetails selectedId="one" onSelect={onSelect} onHide={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: 'Remove from board' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Device storage is full')
  expect(onSelect).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Remove from board' }))
  await waitFor(() => expect(onSelect).toHaveBeenCalledWith(null))
})

it('retains a typed title across a failed save and submits the whole draft on retry', async () => {
  fixture.state.setLedgerWidgetSettings.mockRejectedValueOnce(new Error('Write failed')).mockImplementationOnce(async (_id, patch) => {
    fixture.state.ledgerPrefs.widgets[0].settings = patch
  })
  render(<WidgetDetails selectedId="one" onSelect={() => {}} onHide={() => {}} />)
  const input = screen.getByRole('textbox', { name: 'Custom widget title' })
  fireEvent.change(input, { target: { value: 'My entire title' } })
  expect(fixture.state.setLedgerWidgetSettings).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Save title' }))
  await screen.findByRole('alert')
  expect(input).toHaveValue('My entire title')
  fireEvent.click(screen.getByRole('button', { name: 'Save title' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save title' })).toBeDisabled())
  expect(fixture.state.setLedgerWidgetSettings).toHaveBeenLastCalledWith('one', { title: 'My entire title' })
})

function pointer(type = 'pointerup') {
  return { type, button: 0, pointerId: 1, clientX: 0, clientY: 0,
    currentTarget: { setPointerCapture() {}, hasPointerCapture: () => false },
    preventDefault() {}, stopPropagation() {},
  } as unknown as PointerEvent<HTMLDivElement>
}

it('waits for a palette insert before selecting its saved ID', async () => {
  const pending = deferred<string>()
  fixture.state.addLedgerWidget.mockReturnValue(pending.promise)
  const selectWidget = vi.fn()
  const { result } = renderHook(() => useBoardDrag({ selectWidget }))
  act(() => result.current.handlePaletteItemPointerDown(pointer(), 'genres'))
  let end!: Promise<void>
  act(() => { end = result.current.handlePaletteItemPointerEnd(pointer()) })
  expect(selectWidget).not.toHaveBeenCalled()
  await act(async () => { pending.resolve('inserted'); await end })
  expect(selectWidget).toHaveBeenCalledWith('inserted')
})

it('leaves the current selection intact when palette insertion fails', async () => {
  fixture.state.addLedgerWidget.mockRejectedValue(new Error('Write failed'))
  const selectWidget = vi.fn()
  const { result } = renderHook(() => useBoardDrag({ selectWidget }))
  act(() => result.current.handlePaletteItemPointerDown(pointer(), 'genres'))
  await act(async () => result.current.handlePaletteItemPointerEnd(pointer()))
  expect(result.current.saveError).toBe('Write failed')
  expect(selectWidget).not.toHaveBeenCalled()
})

it('does not add a widget after a cancelled pointer gesture', async () => {
  const { result } = renderHook(() => useBoardDrag({ selectWidget: vi.fn() }))
  act(() => result.current.handlePaletteItemPointerDown(pointer(), 'genres'))
  await act(async () => result.current.handlePaletteItemPointerEnd(pointer('pointercancel')))
  expect(fixture.state.addLedgerWidget).not.toHaveBeenCalled()
})

it('persists the final resize target even when the pointer returns before the first save finishes', async () => {
  const pending = deferred<void>()
  fixture.state.setLedgerWidgetWidth.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(undefined)
  const { result } = renderHook(() => useBoardDrag({ selectWidget: vi.fn() }))
  act(() => result.current.resizeHandleProps('one', 'e').onPointerDown(pointer('pointerdown')))
  act(() => result.current.resizeHandleProps('one', 'e').onPointerMove({ ...pointer(), clientX: 1000 }))
  expect(fixture.state.setLedgerWidgetWidth).toHaveBeenCalledTimes(1)
  act(() => result.current.resizeHandleProps('one', 'e').onPointerMove(pointer()))
  act(() => result.current.resizeHandleProps('one', 'e').onPointerUp(pointer()))
  await act(async () => pending.resolve())
  expect(fixture.state.setLedgerWidgetWidth).toHaveBeenCalledTimes(2)
  expect(fixture.state.setLedgerWidgetWidth).toHaveBeenLastCalledWith('one', 'md')
})

it("retains the final target of each resize gesture while earlier storage is pending", async () => {
  const pending = deferred<void>()
  fixture.state.ledgerPrefs.widgets.push({ id: "two", panel: "genres", width: "md", settings: { title: "Second" } })
  fixture.state.setLedgerWidgetWidth.mockReturnValueOnce(pending.promise).mockResolvedValue(undefined)
  const { result } = renderHook(() => useBoardDrag({ selectWidget: vi.fn() }))
  act(() => result.current.resizeHandleProps("one", "e").onPointerDown(pointer()))
  act(() => result.current.resizeHandleProps("one", "e").onPointerMove({ ...pointer(), clientX: 1000 }))
  act(() => result.current.resizeHandleProps("one", "e").onPointerMove(pointer()))
  act(() => result.current.resizeHandleProps("one", "e").onPointerUp(pointer()))
  act(() => result.current.resizeHandleProps("two", "e").onPointerDown(pointer()))
  act(() => result.current.resizeHandleProps("two", "e").onPointerMove({ ...pointer(), clientX: 1000 }))
  act(() => result.current.resizeHandleProps("two", "e").onPointerUp(pointer()))
  await act(async () => pending.resolve())
  expect(fixture.state.setLedgerWidgetWidth.mock.calls).toEqual([["one", "full"], ["one", "md"], ["two", "full"]])
})
