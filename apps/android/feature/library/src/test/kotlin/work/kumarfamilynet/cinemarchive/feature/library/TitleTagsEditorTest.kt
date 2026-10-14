package work.kumarfamilynet.cinemarchive.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleTagsEditorTest {
    @Test fun trimsInputAndTrailingCommasLikeWeb() {
        assertEquals(listOf("Family night"), appendTitleTag(emptyList(), "  Family night,,,  "))
        assertEquals(listOf("Family "), appendTitleTag(emptyList(), "Family ,"))
        assertEquals(emptyList<String>(), appendTitleTag(emptyList(), " ,,, "))
    }
    @Test fun duplicateCheckIsExactCaseAndExistingTagsAreNotRewritten() {
        assertEquals(listOf("Tag", "tag"), appendTitleTag(listOf("Tag"), "tag"))
        assertEquals(listOf("Tag"), appendTitleTag(listOf("Tag"), " Tag,"))
        assertEquals(listOf(" Legacy ", "New"), appendTitleTag(listOf(" Legacy "), "New"))
    }
}
