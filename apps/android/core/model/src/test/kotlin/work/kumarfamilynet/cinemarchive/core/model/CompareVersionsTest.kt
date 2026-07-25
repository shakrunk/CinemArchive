package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareVersionsTest {

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, compareVersions("1.12.1", "1.12.1"))
    }

    @Test
    fun `a leading v is ignored`() {
        assertEquals(0, compareVersions("v1.12.1", "1.12.1"))
    }

    @Test
    fun `compares numerically, not lexicographically`() {
        // The whole reason this isn't String.compareTo: "1.10.0" < "1.9.0" as text.
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersions("1.9.0", "1.10.0") < 0)
    }

    @Test
    fun `major beats minor and patch`() {
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `patch is compared`() {
        assertTrue(compareVersions("1.12.2", "1.12.1") > 0)
        assertTrue(compareVersions("1.12.1", "1.12.2") < 0)
    }

    @Test
    fun `missing segments are treated as zero`() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertTrue(compareVersions("1.2.1", "1.2") > 0)
    }

    @Test
    fun `pre-release and build metadata are ignored`() {
        assertEquals(0, compareVersions("1.12.1-beta.1", "1.12.1"))
        assertEquals(0, compareVersions("1.12.1+build7", "1.12.1"))
    }

    @Test
    fun `non-numeric segments degrade to zero rather than throwing`() {
        assertEquals(0, compareVersions("1.x.1", "1.0.1"))
    }
}
