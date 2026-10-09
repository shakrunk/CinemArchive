package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.NavigationDestination
import work.kumarfamilynet.cinemarchive.core.model.NavigationPreferences

@RunWith(RobolectricTestRunner::class)
class NavigationPreferencesRepositoryTest {
    @Test fun settingsSurviveRepositoryRecreationAndResetWithoutChangingAppearance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = PreferencesRepository(context)
        repository.resetNavigation()
        val palette = repository.observePalette().first()
        repository.moveNavigation(NavigationDestination.LISTS, -1)
        repository.showNavigation(NavigationDestination.DISCOVER, false)
        repository.setNavigationCompact(true)
        val restored = PreferencesRepository(context).observeNavigation().first()
        assertEquals(listOf(NavigationDestination.DISCOVER, NavigationDestination.LIBRARY, NavigationDestination.UP_NEXT,
            NavigationDestination.LISTS, NavigationDestination.LEDGER), restored.order)
        assertEquals(setOf(NavigationDestination.DISCOVER), restored.hidden)
        assertTrue(restored.compact)
        repository.resetNavigation()
        assertEquals(NavigationPreferences(), repository.observeNavigation().first())
        assertEquals(palette, repository.observePalette().first())
    }

    @Test fun serializedVisibilityChangesKeepOneDestination() = runBlocking {
        val repository = PreferencesRepository(ApplicationProvider.getApplicationContext())
        repository.resetNavigation()
        NavigationDestination.entries.forEach { repository.showNavigation(it, false) }
        assertEquals(listOf(NavigationDestination.LISTS), repository.observeNavigation().first().visible)
        repository.showNavigation(NavigationDestination.LIBRARY, true)
        repository.showNavigation(NavigationDestination.LISTS, false)
        assertEquals(listOf(NavigationDestination.LIBRARY), repository.observeNavigation().first().visible)
        repository.resetNavigation()
    }
}
