import type { Title } from '../store/mockData'

interface ExportEnvelope {
  version: number
  exportedAt: string
  titles: Title[]
}

export function exportLibrary(titles: Title[]): void {
  const payload: ExportEnvelope = {
    version: 1,
    exportedAt: new Date().toISOString().slice(0, 10),
    titles,
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `cinemarchive-${payload.exportedAt}.json`
  a.click()
  URL.revokeObjectURL(url)
}

export async function parseImportFile(file: File): Promise<Title[]> {
  const text = await file.text()
  let parsed: any
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('Invalid file — not valid JSON.')
  }

  // Accept both a versioned envelope { version, titles } and a bare array
  const raw: any[] = Array.isArray(parsed) ? parsed : parsed?.titles
  if (!Array.isArray(raw)) {
    throw new Error('Invalid export file: expected a "titles" array.')
  }

  return raw.map((t: any, i: number) => {
    if (!t.tmdbId || !t.type || !t.title) {
      throw new Error(`Entry ${i + 1} is missing required fields (tmdbId, type, title).`)
    }
    // Regenerate all IDs to avoid UUID collisions with existing records
    const newTitleId = crypto.randomUUID()
    return {
      ...t,
      id: newTitleId,
      seasons: (t.seasons ?? []).map((s: any) => ({ ...s, id: crypto.randomUUID() })),
      viewings: (t.viewings ?? []).map((v: any) => ({
        ...v,
        id: crypto.randomUUID(),
        titleId: newTitleId,
      })),
    } as Title
  })
}
