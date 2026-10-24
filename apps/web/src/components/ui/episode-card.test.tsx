import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EpisodePanel } from './episode-card'
import { fetchEpisodeCast } from 'src/lib/media'
import { makeEpisode, makeSeason } from '@/test/fixtures'
import type { CastMember } from 'src/store/mockData'

vi.mock('src/lib/media', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/media')>()),
  fetchEpisodeCast: vi.fn(),
}))

const member = (id: number, name: string): CastMember => ({ tmdbPersonId: id, name, character: `${name}'s role`, order: id })

function renderPanel() {
  const episode = makeEpisode({ episodeNumber: 4 })
  const season = makeSeason({ seasonNumber: 2, episodes: [episode] })
  return render(
    <EpisodePanel episode={episode} season={season} titleId="t1" tmdbId={7} isSharedView isSpiderNoir={false} onPersonClick={vi.fn()} />
  )
}

beforeEach(() => {
  vi.mocked(fetchEpisodeCast).mockResolvedValue({
    cast: [member(1, 'Regular One'), member(2, 'Regular Two')],
    guestStars: [member(3, 'Guest Star')],
  })
})

afterEach(() => { cleanup(); vi.resetAllMocks() })

describe('episode cast', () => {
  it('shows guest stars and keeps the series regulars behind a toggle', async () => {
    renderPanel()
    expect(await screen.findByRole('button', { name: 'View details for Guest Star' })).toBeVisible()
    expect(screen.queryByRole('button', { name: 'View details for Regular One' })).not.toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: /2 series regulars in this episode/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'View details for Regular One' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'View details for Regular Two' })).toBeVisible()
  })
})
