package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.*
import org.junit.Test

class NavigationPreferencesTest {
    @Test fun restoreDropsUnknownDuplicatesAndAppendsNewDestinations() {
        val restored = NavigationPreferences.restore(listOf("lists", "unknown", "library", "lists"), setOf("unknown", "ledger"), true)
        assertEquals(listOf(NavigationDestination.LISTS, NavigationDestination.LIBRARY, NavigationDestination.DISCOVER,
            NavigationDestination.UP_NEXT, NavigationDestination.LEDGER), restored.order)
        assertEquals(setOf(NavigationDestination.LEDGER), restored.hidden)
        assertTrue(restored.compact)
    }

    @Test fun corruptAllHiddenStateRecoversOneVisibleDestination() {
        val restored = NavigationPreferences.restore(listOf("lists"), NavigationDestination.entries.map { it.key }.toSet(), false)
        assertEquals(listOf(NavigationDestination.LISTS), restored.visible)
        assertEquals(restored, restored.show(NavigationDestination.LISTS, false))
    }

    @Test fun reorderHiddenTabPreservesVisibilityAndBoundaries() {
        val original = NavigationPreferences().show(NavigationDestination.LIBRARY, false)
        val moved = original.move(NavigationDestination.LIBRARY, -1)
        assertEquals(NavigationDestination.LIBRARY, moved.order.first())
        assertEquals(original.hidden, moved.hidden)
        assertEquals(original.visible, moved.visible)
        assertEquals(moved, moved.move(NavigationDestination.LIBRARY, -1))
        assertEquals(NavigationDestination.LIBRARY, moved.show(NavigationDestination.LIBRARY, true).visible.first())
    }

    @Test fun finalVisibleTabCannotBeHiddenAndResetDefaultsRemainComplete() {
        val last = NavigationDestination.entries.fold(NavigationPreferences()) { prefs, id -> prefs.show(id, false) }
        assertEquals(listOf(NavigationDestination.LISTS), last.visible)
        assertEquals(last, last.show(NavigationDestination.LISTS, false))
        assertEquals(5, NavigationPreferences().visible.size)
    }
}
