import { useState, useEffect } from 'react'
import type { MediaType } from 'src/store/mockData'
import { fetchTitleVideos, fetchTitleImages, fetchWatchProviders, type TitleVideo, type WatchProviders } from 'src/lib/media'


/** TMDB extras for the open drawer — trailers, watch providers, and the best logo +
 *  backdrop — each fetched while the drawer is open and cleared once it closes. */
export function useTitleMedia(isOpen: boolean, tmdbId: number | undefined, type: MediaType | undefined) {
  const [videos, setVideos] = useState<TitleVideo[]>([])
  const [watchProviders, setWatchProviders] = useState<WatchProviders | null>(null)
  const [logoUrl, setLogoUrl] = useState<string | null>(null)
  const [heroBackdropUrl, setHeroBackdropUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!isOpen || !tmdbId || !type) {
      // Deferred to satisfy react-hooks/set-state-in-effect.
      const t = setTimeout(() => setVideos([]), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleVideos(tmdbId, type).then((v) => {
      if (!cancelled) setVideos(v)
    })
    return () => { cancelled = true }
  }, [isOpen, tmdbId, type])

  useEffect(() => {
    if (!isOpen || !tmdbId || !type) {
      const t = setTimeout(() => setWatchProviders(null), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchWatchProviders(tmdbId, type).then((wp) => {
      if (!cancelled) setWatchProviders(wp)
    })
    return () => { cancelled = true }
  }, [isOpen, tmdbId, type])

  // Images: best logo + best backdrop at original resolution, fetched together.
  // No backdropUrl guard — we fetch for any title with a tmdbId so titles
  // without a stored backdrop can still get a cinematic hero + logo.
  useEffect(() => {
    if (!isOpen || !tmdbId || !type) {
      const t = setTimeout(() => { setLogoUrl(null); setHeroBackdropUrl(null) }, 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleImages(tmdbId, type).then(({ logoUrl: logo, backdropUrl: backdrop }) => {
      if (!cancelled) {
        setLogoUrl(logo)
        setHeroBackdropUrl(backdrop)
      }
    })
    return () => { cancelled = true }
  }, [isOpen, tmdbId, type])

  return { videos, watchProviders, logoUrl, heroBackdropUrl }
}
