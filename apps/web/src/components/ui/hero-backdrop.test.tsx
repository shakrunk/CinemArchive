import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@testing-library/react'
import { mockTitles } from 'src/store/mockData'
import { HeroBackdrop } from './hero-backdrop'

afterEach(cleanup)

describe('hero backdrop transfer size', () => {
  it.each([undefined, 'https://image.tmdb.org/t/p/original/override.jpg'])(
    'caps historical stored and override URLs at render time (%s)', override => {
      const { container } = render(<HeroBackdrop title={{ ...mockTitles[0], backdropUrl: 'https://image.tmdb.org/t/p/original/old.jpg' }} backdropOverride={override} onPosterClick={() => {}}>Title</HeroBackdrop>)
      expect(container.querySelector('img[aria-hidden="true"]')).toHaveAttribute('src', `https://image.tmdb.org/t/p/w1280/${override ? 'override' : 'old'}.jpg`)
    }
  )
})
