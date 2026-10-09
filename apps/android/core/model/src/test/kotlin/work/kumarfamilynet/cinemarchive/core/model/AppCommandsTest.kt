package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.*
import org.junit.Test

class AppCommandsTest {
    @Test fun rankingMatchesWebPriorityBoundaryAndKeywordRules() {
        val commands = listOf(
            AppCommand("exact", "Alien"),
            AppCommand("prefix", "Aliens"),
            AppCommand("word", "The Alien Returns"),
            AppCommand("substring", "Superalien"),
            AppCommand("keyword", "Film", keywords = "space alien"),
            AppCommand("keyword-sub", "Other", keywords = "superalien"),
            AppCommand("hint", "Unrelated", hint = "alien"),
        )
        assertEquals(listOf("exact", "prefix", "word", "substring", "keyword", "keyword-sub"),
            rankCommands(commands.reversed(), " ALIEN ").map { it.id })
    }
    @Test fun emptyQueryPreservesDefaultOrderAndResultsAlwaysBounded() {
        val commands = (1..20).map { AppCommand("id$it", "Film $it") }
        assertEquals(commands.take(8), rankCommands(commands, "  "))
        assertEquals(8, rankCommands(commands, "film").size)
        assertTrue(rankCommands(commands, "missing").isEmpty())
    }
    @Test fun equalKeywordScoresUseShorterLabelThenStableIdAndEscapeQuery() {
        val commands = listOf(AppCommand("z", "Same", keywords = "[x]"),
            AppCommand("a", "Same", keywords = "[x]"), AppCommand("long", "Longer", keywords = "[x]"))
        assertEquals(listOf("a", "z", "long"), rankCommands(commands, "[x]").map { it.id })
    }
    @Test fun everyOwnerActionHasStableIdentityIncludingHiddenNavigationAndTickets() {
        val commands = ownerCommands(emptyList())
        assertEquals(commands.size, commands.map { it.id }.toSet().size)
        assertTrue(commands.map { it.id }.containsAll(listOf("add", "tickets", "marquee", "profile", "friends", "grid", "list", "discover", "library", "ledger", "upnext", "lists")))
        assertEquals("tickets", rankCommands(commands, "I've got tickets").first().id)
    }
}

