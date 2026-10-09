package work.kumarfamilynet.cinemarchive.feature.friends

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class TitleSocialStateTest {
    private val draft = RecommendationDraft(42, MediaType.MOVIE, "Cinema", 2026, null)

    @Test fun duplicatePostIsBlockedAndSuccessfulPostClearsDraft() = runTest {
        val source = FakeSource()
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        controller.draft("  hello  ")
        controller.post(); controller.post(); advanceUntilIdle()
        assertEquals(listOf("hello"), source.posts)
        assertEquals("", controller.state.value.draft)
        assertEquals("hello", controller.state.value.comments.single().body)
    }

    @Test fun failedPostPreservesDraftAndRetryPostsOnlyOnce() = runTest {
        val source = FakeSource().apply { failPost = true }
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        controller.draft("keep this")
        controller.post(); advanceUntilIdle()
        assertEquals("keep this", controller.state.value.draft)
        assertNotNull(controller.state.value.error)
        source.failPost = false
        controller.post(); advanceUntilIdle()
        assertEquals(listOf("keep this"), source.posts)
    }

    @Test fun refreshFailureAfterPostCannotResubmitSuccessfulComment() = runTest {
        val source = FakeSource()
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        source.failComments = true
        controller.draft("saved")
        controller.post(); advanceUntilIdle()
        controller.post(); advanceUntilIdle()
        assertEquals(listOf("saved"), source.posts)
        assertEquals("", controller.state.value.draft)
        assertEquals("saved", controller.state.value.comments.single().body)
        assertNotNull(controller.state.value.error)
    }

    @Test fun onlyOwnExactCommentCanBeDeleted() = runTest {
        val source = FakeSource().apply { commentRows = listOf(comment("mine", "owner"), comment("other", "friend")) }
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        controller.delete("other"); advanceUntilIdle()
        assertTrue(source.deleted.isEmpty())
        controller.delete("mine"); advanceUntilIdle()
        assertEquals(listOf("mine"), source.deleted)
        assertEquals(listOf("other"), controller.state.value.comments.map { it.id })
    }

    @Test fun reactionCanBeReplacedThenRemovedWithoutDuplicates() = runTest {
        val source = FakeSource()
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        controller.react("👍"); controller.react("❤️"); advanceUntilIdle()
        controller.react("❤️"); advanceUntilIdle()
        assertEquals("❤️", controller.state.value.reactions.single().emoji)
        controller.react("❤️"); advanceUntilIdle()
        assertEquals(listOf("👍", "❤️", null), source.reacted)
        assertTrue(controller.state.value.reactions.isEmpty())
    }

    @Test fun closingDuringLoadClearsPrivateStateAndPreventsFurtherCalls() = runTest {
        val source = FakeSource().apply { waitComments = CompletableDeferred() }
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        controller.close()
        source.waitComments!!.complete(Unit)
        controller.draft("must not send"); controller.post(); advanceUntilIdle()
        assertTrue(controller.state.value.comments.isEmpty())
        assertNull(controller.state.value.error)
        assertTrue(source.posts.isEmpty())
        assertEquals(0, source.reactionReads)
    }

    @Test fun accountTransitionRejectsOldLoadResultAndWrites() = runTest {
        val source = FakeSource().apply { waitComments = CompletableDeferred(); commentRows = listOf(comment("private", "owner")) }
        val controller = TitleDiscussionController(source, "title", "owner", this)
        controller.load(); advanceUntilIdle()
        source.active = false
        source.waitComments!!.complete(Unit); advanceUntilIdle()
        controller.draft("wrong account"); controller.post(); advanceUntilIdle()
        assertTrue(controller.state.value.comments.isEmpty())
        assertTrue(source.posts.isEmpty())
        assertEquals(0, source.reactionReads)
    }

    @Test fun recipientErrorsAreIndependentAndRetryDoesNotResendOtherFriend() = runTest {
        val source = FakeSource().apply { failRecipients = setOf("two") }
        val controller = RecommendationController(source, "owner", draft, this)
        controller.load(); advanceUntilIdle()
        controller.send("one", "note", "")
        controller.send("one", "duplicate", "")
        controller.send("two", "note", "")
        advanceUntilIdle()
        assertEquals(setOf("one"), controller.state.value.sent)
        assertEquals(setOf("two"), controller.state.value.errors.keys)
        source.failRecipients = emptySet()
        controller.send("two", "retry", ""); advanceUntilIdle()
        assertEquals(listOf("one", "two", "two"), source.sentRecipients)
        assertEquals(setOf("one", "two"), controller.state.value.sent)
        assertTrue(controller.state.value.errors.isEmpty())
    }

    @Test fun existingRecommendationCanBeSentAgainButPendingFriendsCannot() = runTest {
        val source = FakeSource().apply { alreadySent = mapOf("one" to RecommendationStatus.DISMISSED) }
        val controller = RecommendationController(source, "owner", draft, this)
        controller.load(); advanceUntilIdle()
        controller.send("pending", "", "")
        controller.send("one", "", ""); advanceUntilIdle()
        assertEquals(listOf("one"), source.sentRecipients)
    }

    @Test fun closedOrInactiveRecommendationCannotPublishLateResultOrSend() = runTest {
        val source = FakeSource().apply { waitSend = CompletableDeferred() }
        val controller = RecommendationController(source, "owner", draft, this)
        controller.load(); advanceUntilIdle()
        controller.send("one", "", ""); advanceUntilIdle()
        source.active = false
        source.waitSend!!.complete(Unit); advanceUntilIdle()
        controller.send("two", "", ""); advanceUntilIdle()
        assertTrue(controller.state.value.friends.isEmpty())
        assertTrue(controller.state.value.sent.isEmpty())
        assertEquals(listOf("one"), source.sentRecipients)
        controller.close()
    }

    private fun comment(id: String, author: String) = TitleComment(id, author, author, null, "body", "2026-10-08T12:00:00Z")

    private class FakeSource : TitleSocialSource {
        var active = true
        var failPost = false
        var failComments = false
        var failRecipients = emptySet<String>()
        var alreadySent = emptyMap<String, RecommendationStatus>()
        var commentRows = emptyList<TitleComment>()
        var waitComments: CompletableDeferred<Unit>? = null
        var waitSend: CompletableDeferred<Unit>? = null
        var reactionReads = 0
        val posts = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val reacted = mutableListOf<String?>()
        val sentRecipients = mutableListOf<String>()
        override fun isActive() = active
        override suspend fun comments(titleId: String): List<TitleComment> {
            waitComments?.await()
            check(!failComments) { "Offline" }
            return commentRows
        }
        override suspend fun reactions(titleId: String): List<TitleReaction> { reactionReads++; return emptyList() }
        override suspend fun post(titleId: String, body: String): TitleComment {
            check(!failPost) { "Offline" }
            posts += body
            return TitleComment("new", "owner", "Owner", null, body, "2026-10-08T12:00:00Z").also { commentRows += it }
        }
        override suspend fun delete(commentId: String) { deleted += commentId }
        override suspend fun react(titleId: String, emoji: String?) { reacted += emoji }
        override suspend fun friends() = listOf(friend("one"), friend("two"), friend("pending", FriendshipStatus.PENDING))
        override suspend fun sent(tmdbId: Int, type: MediaType) = alreadySent
        override suspend fun recommend(recipientId: String, draft: RecommendationDraft, note: String, url: String) {
            sentRecipients += recipientId
            waitSend?.await()
            check(recipientId !in failRecipients) { "Try again" }
        }
        private fun friend(id: String, status: FriendshipStatus = FriendshipStatus.ACCEPTED) =
            Friendship(id, status, "owner", null, "", "", id, null)
    }
}
