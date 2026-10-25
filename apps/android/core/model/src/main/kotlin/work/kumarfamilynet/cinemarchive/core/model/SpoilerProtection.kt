package work.kumarfamilynet.cinemarchive.core.model

/**
 * Spoiler protection: with it on, an episode's still, name and synopsis are hidden until it has
 * been watched. In a shared library the watch history is the owner's, not the viewer's, so every
 * episode stays hidden. Mirrors `isEpisodeSpoilerHidden` in the web app's `src/store/episodeUtils.ts`.
 */
fun isEpisodeSpoilerHidden(watched: Boolean, spoilerProtection: Boolean, sharedView: Boolean = false): Boolean =
    spoilerProtection && (sharedView || !watched)
