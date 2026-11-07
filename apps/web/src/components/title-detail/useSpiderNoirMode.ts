import { useState, useEffect, useRef, useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { getUnlockedModes, getEarnedModes } from 'src/store/episodeUtils'
import type { Title } from 'src/store/mockData'
import { transitionSpiderNoir } from 'src/lib/theme'
import { SPIDER_NOIR_TMDB_ID } from 'src/lib/easterEggThemes'

export type SelectorMode = 'normal' | 'bw' | 'color'
const EASTER_EGG_KEY = 'spider_noir_color'
const SPIDER_NOIR_LOGO_NORMAL = 'https://image.tmdb.org/t/p/original/o2D8loRUDlEuOf7BMRgulerNJ6p.png'
const SPIDER_NOIR_LOGO_COLOR  = 'https://image.tmdb.org/t/p/original/ubGbGqmbfWW1B7kNXDI1KrooK7S.png'
const SPIDER_NOIR_LOGO_BW     = 'https://image.tmdb.org/t/p/original/tcS3i7X9XvYcFGEEnjHG147sQrq.png'

function getSpiderNoirActiveMode(title: Title): 'bw' | 'color' | null {
  let lastMode: 'bw' | 'color' | null = null
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) lastMode = we.colorMode
      }
      for (const rv of ep.reviews) {
        if (rv.colorMode) lastMode = rv.colorMode
      }
    }
  }
  return lastMode
}

/** Spider-Noir's B&W / Color viewing modes: which are unlocked and earned, the
 *  drawer's selection (seeded from the pinned mode, then the last watch event), the
 *  body classes + View Transition that tint the app to match, and the matching logo. */
export function useSpiderNoirMode(title: Title | null, isDetailDrawerOpen: boolean) {
  const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID
  const pinnedModes = useAppStore((s) => s.pinnedModes)
  const setPinnedMode = useAppStore((s) => s.setPinnedMode)
  const unlockTheme = useAppStore((s) => s.unlockTheme)

  const unlockedModes = useMemo(
    () => (isSpiderNoir && title ? getUnlockedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )
  const earnedModes = useMemo(
    () => (isSpiderNoir && title ? getEarnedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )

  // Earning the black & white mode unlocks the app-wide Noir theme (Settings →
  // Appearance). Keyed on the "earned" signal so it fires regardless of whether
  // the title was already watched before this session.
  useEffect(() => {
    if (earnedModes.has('bw')) unlockTheme('noir')
  }, [earnedModes, unlockTheme])

  const prevNoirModeRef = useRef<'bw' | 'color' | null | undefined>(undefined)
  const [noirAnim, setNoirAnim] = useState<'bw' | 'color' | null>(null)
  const noirAnimTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [manualMode, setManualMode] = useState<SelectorMode>('normal')

  // closeDetailDrawer only sets isDetailDrawerOpen=false, not selectedTitleId=null,
  // so title and isSpiderNoir remain valid here even when the drawer is closed.
  const pinnedModeRaw = (title ? (pinnedModes[`${title.id}:${EASTER_EGG_KEY}`] ?? null) : null) as 'bw' | 'color' | null

  // Seed manualMode from pinned → last watch event → normal when the drawer opens.
  // setTimeout(0) defers the setState out of the effect body to satisfy react-hooks/set-state-in-effect.
  useEffect(() => {
    if (!isSpiderNoir || !title || !isDetailDrawerOpen) return
    const derived = getSpiderNoirActiveMode(title)
    const seeded = pinnedModeRaw ?? derived ?? 'normal'
    const t = setTimeout(() => setManualMode(seeded as SelectorMode), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title?.id, isDetailDrawerOpen])

  // While drawer is open: use manual selection. When closed: use pinned (if any).
  const effectiveNoirMode: 'bw' | 'color' | null = isSpiderNoir
    ? (isDetailDrawerOpen ? (manualMode !== 'normal' ? (manualMode as 'bw' | 'color') : null) : pinnedModeRaw)
    : null

  useEffect(() => {
    const ALL = ['spider-noir-bw', 'spider-noir-color', 'spider-noir-bw-enter', 'spider-noir-color-enter'] as const
    const prevMode = prevNoirModeRef.current

    // Skip the transition on the very first render (prev === undefined) —
    // the drawer isn't open yet so there's nothing to animate between.
    // Also skip if the mode hasn't actually changed.
    const isVisualChange = prevMode !== undefined && prevMode !== effectiveNoirMode

    function applyClasses() {
      document.body.classList.remove(...ALL)
      if (effectiveNoirMode) {
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color')
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw-enter' : 'spider-noir-color-enter')
      }
    }

    if (isVisualChange) {
      // The View Transition must capture the current (pre-change) DOM state
      // before applyClasses runs. We pass applyClasses as the commit so the
      // API snapshots before/after correctly — this works for both the enter
      // (normal → noir) and exit (noir → normal) directions.
      transitionSpiderNoir(applyClasses)
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
      if (effectiveNoirMode) {
        // Entering or switching: cast the web overlay on top of the VT reveal.
        // Deferred so it runs as an async callback (not synchronously in the
        // effect body) — avoids the react-hooks/set-state-in-effect lint rule.
        noirAnimTimerRef.current = setTimeout(() => {
          setNoirAnim(effectiveNoirMode)
          noirAnimTimerRef.current = setTimeout(() => {
            setNoirAnim(null)
            document.body.classList.remove('spider-noir-bw-enter', 'spider-noir-color-enter')
          }, 2100)
        }, 0)
      } else {
        // Exiting: dismiss any lingering web overlay so it doesn't hang over
        // the returned normal view.
        noirAnimTimerRef.current = setTimeout(() => setNoirAnim(null), 0)
      }
    } else {
      applyClasses()
    }

    prevNoirModeRef.current = effectiveNoirMode

    // Do NOT remove body classes in the cleanup — the cleanup fires before the
    // next effect run, which would cause the exit View Transition to capture
    // no-noir → no-noir (invisible). Class removal is handled by applyClasses
    // in the next run. The timer must still be cleared on unmount.
    return () => {
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
    }
  }, [effectiveNoirMode])

  const noirLogoUrl = isSpiderNoir
    ? (effectiveNoirMode === 'color' ? SPIDER_NOIR_LOGO_COLOR : effectiveNoirMode === 'bw' ? SPIDER_NOIR_LOGO_BW : SPIDER_NOIR_LOGO_NORMAL)
    : null

  function handleModeSelect(mode: SelectorMode) {
    setManualMode(mode)
  }

  function handleTogglePin(mode: 'bw' | 'color') {
    if (!title) return
    const newVariant = pinnedModeRaw === mode ? null : mode
    setPinnedMode(title.id, EASTER_EGG_KEY, newVariant)
  }

  return {
    isSpiderNoir, unlockedModes, earnedModes, manualMode, pinnedModeRaw, noirAnim, noirLogoUrl,
    handleModeSelect, handleTogglePin,
  }
}
