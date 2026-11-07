import type { Viewing } from 'src/store/mockData'

// Undated viewings are pre-platform (watched before joining) and order as oldest.
export function viewingTime(v: Viewing): number {
  return v.date ? new Date(v.date).getTime() : -Infinity
}
