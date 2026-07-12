import type { CinemaOuting, Title } from '../store/mockData'

interface ExportEnvelope {
  version: number
  exportedAt: string
  titles: Title[]
  outings: CinemaOuting[]
}

export function exportLibrary(titles: Title[], outings: CinemaOuting[] = []): void {
  const payload: ExportEnvelope = {
    version: 1,
    exportedAt: new Date().toISOString().slice(0, 10),
    titles,
    outings,
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `cinemarchive-${payload.exportedAt}.json`
  a.click()
  URL.revokeObjectURL(url)
}

export interface ImportResult {
  titles: Title[]
  outings: CinemaOuting[]
}

export async function parseImportFile(file: File): Promise<ImportResult> {
  const text = await file.text()
  let parsed: any
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('Invalid file — not valid JSON.')
  }

  // Accept both a versioned envelope { version, titles, outings } and a bare titles array
  const raw: any[] = Array.isArray(parsed) ? parsed : parsed?.titles
  if (!Array.isArray(raw)) {
    throw new Error('Invalid export file: expected a "titles" array.')
  }
  // Older exports (pre-cinema-outings) simply have no "outings" key — tolerate its absence.
  const rawOutings: any[] = Array.isArray(parsed?.outings) ? parsed.outings : []

  // Every ID in the export is regenerated to avoid UUID collisions with
  // existing records. These maps let the outing⇄viewing back-references
  // (completedViewingId, viewings[].outingId — rule §5.13) survive that
  // regeneration alongside the title-ID remap below.
  const titleIdMap = new Map<string, string>()
  const viewingIdMap = new Map<string, string>()

  const titles: Title[] = raw.map((t: any, i: number) => {
    if (!t.tmdbId || !t.type || !t.title) {
      throw new Error(`Entry ${i + 1} is missing required fields (tmdbId, type, title).`)
    }
    const newTitleId = crypto.randomUUID()
    if (t.id) titleIdMap.set(t.id, newTitleId)
    return {
      ...t,
      id: newTitleId,
      seasons: (t.seasons ?? []).map((s: any) => ({ ...s, id: crypto.randomUUID() })),
      viewings: (t.viewings ?? []).map((v: any) => {
        const newViewingId = crypto.randomUUID()
        if (v.id) viewingIdMap.set(v.id, newViewingId)
        // outingId is remapped in a second pass below, once regenerated
        // outing IDs exist.
        return { ...v, id: newViewingId, titleId: newTitleId }
      }),
    } as Title
  })

  const outingIdMap = new Map<string, string>()
  const outings: CinemaOuting[] = rawOutings.map((o: any) => {
    const newOutingId = crypto.randomUUID()
    if (o.id) outingIdMap.set(o.id, newOutingId)
    return {
      ...o,
      id: newOutingId,
      titleId: titleIdMap.get(o.titleId) ?? o.titleId,
      completedViewingId: o.completedViewingId ? viewingIdMap.get(o.completedViewingId) : undefined,
    } as CinemaOuting
  })

  // Second pass: remap each viewing's outingId now that outings have their
  // final regenerated IDs.
  for (const title of titles) {
    title.viewings = title.viewings.map((v) => (v.outingId ? { ...v, outingId: outingIdMap.get(v.outingId) } : v))
  }

  return { titles, outings }
}
