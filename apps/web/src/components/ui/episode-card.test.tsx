import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EpisodeCard, EpisodePanel } from './episode-card'
import { fetchEpisodeCast } from 'src/lib/media'
import { makeEpisode, makeSeason, makeWatchEvent } from '@/test/fixtures'
import { useAppStore } from 'src/store/useAppStore'
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

afterEach(() => { cleanup(); vi.resetAllMocks(); useAppStore.setState({ spoilerProtection: false }) })

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

describe('spoiler protection', () => {
  const spoiler = { episodeName: 'The Twist', synopsis: 'Everyone was a ghost.', stillUrl: 'https://img.test/still.jpg' }

  function renderCard(episode = makeEpisode({ episodeNumber: 3, ...spoiler }), isSharedView = false) {
    const season = makeSeason({ episodes: [episode] })
    return render(
      <EpisodeCard episode={episode} season={season} titleId="t1" isSelected={false} onSelect={vi.fn()} isSharedView={isSharedView} isSpiderNoir={false} />
    )
  }

  it('shows the still, name and synopsis when off', () => {
    const { container } = renderCard()
    expect(screen.getByText('The Twist')).toBeVisible()
    expect(screen.getByText('Everyone was a ghost.')).toBeVisible()
    expect(container.querySelector('img')).not.toBeNull()
  })

  it('hides an unwatched episode, including from its accessible name', () => {
    useAppStore.setState({ spoilerProtection: true })
    const { container } = renderCard()
    expect(screen.queryByText('The Twist')).not.toBeInTheDocument()
    expect(screen.queryByText('Everyone was a ghost.')).not.toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('Episode 3')).toBeVisible()
    expect(screen.getByText('Hidden to avoid spoilers')).toBeVisible()
    expect(container.innerHTML).not.toContain('The Twist')
  })

  it('leaves a watched episode visible in the owner view', () => {
    useAppStore.setState({ spoilerProtection: true })
    renderCard(makeEpisode({ ...spoiler, watchEvents: [makeWatchEvent()] }))
    expect(screen.getByText('The Twist')).toBeVisible()
  })

  it('hides every episode in a shared view, since its watches belong to the owner', () => {
    useAppStore.setState({ spoilerProtection: true })
    renderCard(makeEpisode({ ...spoiler, watchEvents: [makeWatchEvent()] }), true)
    expect(screen.queryByText('The Twist')).not.toBeInTheDocument()
  })

  it('lets the panel reveal the hidden still and synopsis on request', () => {
    useAppStore.setState({ spoilerProtection: true })
    vi.mocked(fetchEpisodeCast).mockResolvedValue({ cast: [], guestStars: [] })
    const episode = makeEpisode(spoiler)
    render(
      <EpisodePanel episode={episode} season={makeSeason({ episodes: [episode] })} titleId="t1" tmdbId={7} isSharedView={false} isSpiderNoir={false} onPersonClick={vi.fn()} />
    )
    expect(screen.queryByText('Everyone was a ghost.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(screen.getByText('Everyone was a ghost.')).toBeVisible()
  })
})
