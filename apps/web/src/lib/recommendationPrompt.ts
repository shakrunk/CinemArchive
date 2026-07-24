// Builds a compact prompt for pasting into an LLM chat to get personalized
// recommendations. Keeps token count low: per title, only what's needed to
// identify the media (title/year/type) and what reflects the user's taste
// (watch status, rating) — no synopsis, cast, genres, etc.

import type { Title } from 'src/store/mockData'

const TYPE_LABEL: Record<Title['type'], string> = { movie: 'Movie', tv: 'TV' }

function formatTitleLine(t: Title): string {
  const base = `${t.title} (${t.year}) [${TYPE_LABEL[t.type]}] — ${t.status}`
  return t.rating ? `${base}, ★${t.rating}` : base
}

export function buildRecommendationPrompt(titles: Title[]): string {
  if (titles.length === 0) {
    return "I use a movie/TV tracking app but haven't logged anything yet. Ask me a few quick questions about my taste, then recommend some movies or TV shows."
  }

  const lines = titles
    .slice()
    .sort((a, b) => a.title.localeCompare(b.title))
    .map(formatTitleLine)
    .join('\n')

  return [
    "Here's my movie/TV watch history — title (year) [type] — status, and my rating out of 5 where I've given one:",
    '',
    lines,
    '',
    "Based on this, recommend 8-10 movies or TV shows that match my taste. Don't recommend anything already in the list above.",
  ].join('\n')
}
