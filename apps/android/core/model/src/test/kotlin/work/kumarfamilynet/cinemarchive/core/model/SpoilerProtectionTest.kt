package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the spoiler-protection cases in apps/web/src/components/ui/episode-card.test.tsx. */
class SpoilerProtectionTest {
    @Test fun nothingIsHiddenWhenOff() {
        assertFalse(isEpisodeSpoilerHidden(watched = false, spoilerProtection = false))
        assertFalse(isEpisodeSpoilerHidden(watched = false, spoilerProtection = false, sharedView = true))
    }

    @Test fun hidesOnlyUnwatchedEpisodesForTheOwner() {
        assertTrue(isEpisodeSpoilerHidden(watched = false, spoilerProtection = true))
        assertFalse(isEpisodeSpoilerHidden(watched = true, spoilerProtection = true))
    }

    @Test fun hidesEveryEpisodeInASharedView() {
        assertTrue(isEpisodeSpoilerHidden(watched = true, spoilerProtection = true, sharedView = true))
    }
}
