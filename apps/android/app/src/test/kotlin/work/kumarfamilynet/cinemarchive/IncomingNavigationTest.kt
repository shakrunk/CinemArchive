package work.kumarfamilynet.cinemarchive

import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.data.AuthIdentity

class IncomingNavigationTest {
    private val origin = "https://cinemarchive.kumarfamilynet.work/"

    @Test fun `title list and add match web query semantics`() {
        assertEquals(IncomingNavigation("library", "title-id", null, false),
            IncomingNavigation.parse("${origin}?view=library&title=title-id"))
        assertEquals(IncomingNavigation("lists", null, "list-id", false),
            IncomingNavigation.parse("${origin}?view=lists&list=list-id"))
        assertEquals(IncomingNavigation(null, null, null, true), IncomingNavigation.parse("${origin}?add=1"))
        listOf("0", "true", "", "2").forEach {
            assertFalse(IncomingNavigation.parse("${origin}?add=$it")!!.add)
        }
    }

    @Test fun `all web views are accepted and invalid view keeps current destination`() {
        listOf("discover", "library", "upnext", "ledger", "lists", "profile", "friends").forEach {
            assertEquals(it, IncomingNavigation.parse("${origin}?view=$it")!!.view)
        }
        assertEquals(IncomingNavigation(null, null, null, false),
            IncomingNavigation.parse("${origin}?view=unknown&title=&list="))
    }

    @Test fun `first query value and decoded keys match URLSearchParams`() {
        assertEquals(IncomingNavigation("lists", "first title", "a/b", false),
            IncomingNavigation.parse("${origin}?view=lists&view=library&%74itle=first+title&title=second&list=a%2Fb&add=0&add=1"))
    }

    @Test fun `untrusted origins malformed links and external archive routes do not open owner state`() {
        listOf("http://cinemarchive.kumarfamilynet.work/?add=1",
            "https://evil.example/?add=1",
            "https://cinemarchive.kumarfamilynet.work.evil.example/?add=1",
            "https://user@cinemarchive.kumarfamilynet.work/?add=1",
            "https://cinemarchive.kumarfamilynet.work:8443/?add=1",
            "cinemarchive://auth-callback?title=x", "cinemarchive://share?title=x",
            "${origin}?view=library&share=token", "${origin}?view=library&friend=owner",
            "${origin}?title=%zz", origin).forEach { assertNull(it, IncomingNavigation.parse(it)) }
        assertNotNull(IncomingNavigation.parse("https://cinemarchive.kumarfamilynet.work:443/?add=1"))
    }

    @Test fun `signed out routes defer but signed in routes cannot follow another session`() {
        val route = IncomingNavigation(null, "title", null, false)
        val a = AuthIdentity("a", 1)
        val b = AuthIdentity("b", 2)
        val signedOut = PendingNavigation(route, null, 1)
        assertTrue(signedOut.canOpen(a))
        val signedIn = PendingNavigation(route, a, 2)
        assertTrue(signedIn.canOpen(a))
        assertFalse(signedIn.canOpen(b))
        assertFalse(signedIn.canOpen(AuthIdentity("a", 3)))
    }
}
