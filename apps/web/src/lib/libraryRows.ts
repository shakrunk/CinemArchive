import type { Title } from 'src/store/mockData'

export interface LibrarySection {
  key: string
  name: string | null
  titles: Title[]
}

export type PosterRow =
  | { kind: 'heading'; key: string; section: LibrarySection; first: boolean }
  | { kind: 'posters'; key: string; titles: Title[]; last: boolean }

export function posterRows(titles: Title[], columns: number, sections?: LibrarySection[]): PosterRow[] {
  const rows: PosterRow[] = []
  for (const [index, section] of (sections ?? [{ key: 'all', name: null, titles }]).entries()) {
    if (sections) rows.push({ kind: 'heading', key: `heading:${section.key}`, section, first: index === 0 })
    for (let i = 0; i < section.titles.length; i += columns) {
      const group = section.titles.slice(i, i + columns)
      rows.push({ kind: 'posters', key: `posters:${section.key}:${group[0].id}`, titles: group, last: i + columns >= section.titles.length })
    }
  }
  return rows
}

export type LedgerRow =
  | { kind: 'heading'; key: string; section: LibrarySection; first: boolean }
  | { kind: 'columns'; key: string }
  | { kind: 'title'; key: string; title: Title; number: number }

export function ledgerRows(titles: Title[], sections?: LibrarySection[]): LedgerRow[] {
  return (sections ?? [{ key: 'all', name: null, titles }]).flatMap((section, index) => [
    ...(sections ? [{ kind: 'heading' as const, key: `heading:${section.key}`, section, first: index === 0 }] : []),
    { kind: 'columns' as const, key: `columns:${section.key}` },
    ...section.titles.map((title, i) => ({ kind: 'title' as const, key: `title:${title.id}`, title, number: i + 1 })),
  ])
}

export function sectionName(section: LibrarySection): string {
  return section.name?.replace(/\s+Collection$/i, '') ?? 'Standalone'
}
