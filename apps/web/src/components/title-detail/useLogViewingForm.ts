import { saveSucceeded } from 'src/lib/localSave'
import { useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import type { Title, Viewing, Companion } from 'src/store/mockData'

/** Draft state for the drawer's "Log a viewing" form. The drawer calls this itself,
 *  so a half-filled draft survives switching titles; it resets only after a save. */
export function useLogViewingForm(title: Title | null) {
  const addViewing = useAppStore((s) => s.addViewing)

  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logPrePlatform, setLogPrePlatform] = useState(false)
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')
  // Home viewings can record company too (plan §4.6) — the generalized
  // payoff of putting venue/companions on `viewings` rather than only outings.
  const [logVenue, setLogVenue] = useState('')
  const [logCompanions, setLogCompanions] = useState<Companion[]>([])
  const [showMovieSaved, setShowMovieSaved] = useState(false)

  async function logViewing() {
    if (!title || (!logPrePlatform && !logDate)) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logPrePlatform ? undefined : logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
      venue: logVenue.trim() || undefined,
      companions: logCompanions.length > 0 ? logCompanions : undefined,
    }
    if (!await saveSucceeded(addViewing(title.id, viewing, {
      status: 'watched',
      ...(logRating > 0 ? { rating: logRating } : {}),
    }))) return false
    setShowMovieSaved(true)
    setTimeout(() => {
      setShowMovieSaved(false)
      setShowLogForm(false)
      setLogDate(new Date().toISOString().slice(0, 10))
      setLogPrePlatform(false)
      setLogRating(0)
      setLogNotes('')
      setLogVenue('')
      setLogCompanions([])
    }, 1500)
    return true
  }

  return {
    showLogForm, setShowLogForm, logDate, setLogDate, logPrePlatform, setLogPrePlatform,
    logRating, setLogRating, logNotes, setLogNotes, logVenue, setLogVenue,
    logCompanions, setLogCompanions, showMovieSaved, logViewing,
  }
}

export type LogViewingFormState = ReturnType<typeof useLogViewingForm>
