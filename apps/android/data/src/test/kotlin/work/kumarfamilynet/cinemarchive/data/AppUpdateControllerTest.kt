package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

class AppUpdateControllerTest {

    private val available = UpdateCheckResult.Available(
        currentVersion = "1.44.0",
        latestVersion = "1.45.0",
        apkUrl = "https://example.test/app.apk",
        releasePageUrl = "https://example.test/release",
    )

    private var now = 0L

    private fun TestScope.controller(fetch: suspend () -> UpdateCheckResult) = AppUpdateController(
        fetch = fetch,
        clock = { now },
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun `concurrent checks share one fetch`() = runTest {
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val controller = controller {
            fetches++
            gate.await()
            available
        }

        val first = async { controller.check() }
        val second = async { controller.check() }
        runCurrent()
        assertEquals(UpdateCheckResult.Checking, controller.state.value)

        gate.complete(Unit)
        assertEquals(available, first.await())
        assertEquals(available, second.await())
        assertEquals(1, fetches)
        assertEquals(available, controller.state.value)
    }

    @Test
    fun `a check right after a successful one reuses its result`() = runTest {
        var fetches = 0
        val controller = controller { fetches++; available }

        controller.check()
        now += 5_000
        controller.check()

        assertEquals(1, fetches)
    }

    @Test
    fun `a check after the reuse window goes back to the network`() = runTest {
        var fetches = 0
        val controller = controller { fetches++; available }

        controller.check()
        now += 15_000
        controller.check()

        assertEquals(2, fetches)
    }

    @Test
    fun `a failed check is never reused`() = runTest {
        var fetches = 0
        val controller = controller {
            fetches++
            if (fetches == 1) UpdateCheckResult.Failed("offline") else available
        }

        assertTrue(controller.check() is UpdateCheckResult.Failed)
        now += 1_000
        assertEquals(available, controller.check())
        assertEquals(2, fetches)
    }

    @Test
    fun `a fetch that throws publishes a failure and lets the next check run`() = runTest {
        var fetches = 0
        val controller = controller {
            fetches++
            if (fetches == 1) error("boom") else available
        }

        runCatching { controller.check() }
        assertTrue(controller.state.value is UpdateCheckResult.Failed)

        assertEquals(available, controller.check())
    }
}
