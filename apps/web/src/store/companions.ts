import type { Companion } from './mockData'

/** Companion data can contain plain names from Android or web name/friend
 * objects. Validate at load boundaries, retaining names from both formats. */
export function normalizeCompanions(raw: unknown): Companion[] {
  if (!Array.isArray(raw)) return []
  const companions: Companion[] = []
  for (const entry of raw) {
    const value: unknown = typeof entry === 'string' ? entry : entry?.name
    if (typeof value !== 'string' || !value.trim()) continue
    const companion: Companion = { name: value.trim() }
    if (typeof entry === 'object' && typeof entry.friendUserId === 'string' && entry.friendUserId) {
      companion.friendUserId = entry.friendUserId
    }
    companions.push(companion)
  }
  return companions
}
