package work.kumarfamilynet.cinemarchive.feature.friends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.*

/** Account-bound operations; the runtime supplies a fenced repository and an identity check. */
interface TitleSocialSource {
    fun isActive(): Boolean
    suspend fun comments(titleId: String): List<TitleComment>
    suspend fun reactions(titleId: String): List<TitleReaction>
    suspend fun post(titleId: String, body: String): TitleComment
    suspend fun delete(commentId: String)
    suspend fun react(titleId: String, emoji: String?)
    suspend fun friends(): List<Friendship>
    suspend fun sent(tmdbId: Int, type: MediaType): Map<String, RecommendationStatus>
    suspend fun recommend(recipientId: String, draft: RecommendationDraft, note: String, url: String)
}

class RepositoryTitleSocialSource(
    private val repository: FriendsRepository,
    private val active: () -> Boolean,
) : TitleSocialSource {
    override fun isActive() = active()
    private fun checkAccount() { check(active()) { "Account changed. Reopen this title." } }
    override suspend fun comments(titleId: String) = checked { repository.fetchTitleComments(titleId) }
    override suspend fun reactions(titleId: String) = checked { repository.fetchTitleReactions(titleId) }
    override suspend fun post(titleId: String, body: String) = checked { repository.addTitleComment(titleId, body) }
    override suspend fun delete(commentId: String) { checked { repository.deleteTitleComment(commentId) } }
    override suspend fun react(titleId: String, emoji: String?) { checked { repository.setTitleReaction(titleId, emoji) } }
    override suspend fun friends() = checked { repository.listFriendships() }
    override suspend fun sent(tmdbId: Int, type: MediaType) = checked { repository.fetchSentRecommendationStatus(tmdbId, type) }
    override suspend fun recommend(recipientId: String, draft: RecommendationDraft, note: String, url: String) {
        checked { repository.sendRecommendation(recipientId, draft, note, url) }
    }
    private suspend fun <T> checked(block: suspend () -> T): T {
        checkAccount()
        val result = block()
        coroutineContext.ensureActive()
        checkAccount()
        return result
    }
}

data class TitleDiscussionState(
    val comments: List<TitleComment> = emptyList(),
    val reactions: List<TitleReaction> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val draft: String = "",
    val error: String? = null,
)

/** One instance per account + title. Dispose cancels jobs and clears all private state. */
class TitleDiscussionController(
    private val source: TitleSocialSource,
    private val titleId: String,
    private val viewerId: String,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(TitleDiscussionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var closed = false
    private fun active() = !closed && viewerId.isNotBlank() && source.isActive()
    fun draft(value: String) { if (active()) mutable.value = state.value.copy(draft = value.take(FriendsRules.COMMENT_MAX_LENGTH)) }
    fun load() = run(loading = true) { refresh() }
    fun post() {
        val body = FriendsRules.prepareComment(state.value.draft) ?: return
        run {
            val comment = source.post(titleId, body)
            ensureCurrent()
            mutable.value = state.value.copy(draft = "", comments = state.value.comments + comment)
            refresh()
        }
    }
    fun delete(id: String) {
        if (state.value.comments.none { it.id == id && it.authorId == viewerId }) return
        run {
            source.delete(id)
            ensureCurrent()
            mutable.value = state.value.copy(comments = state.value.comments.filterNot { it.id == id })
        }
    }
    fun react(emoji: String) {
        if (emoji !in FriendsRules.REACTION_EMOJIS) return
        val next = FriendsRules.nextReaction(FriendsRules.myReaction(state.value.reactions, viewerId), emoji)
        run {
            source.react(titleId, next)
            ensureCurrent()
            mutable.value = state.value.copy(reactions = state.value.reactions.filterNot { it.authorId == viewerId } +
                listOfNotNull(next?.let { TitleReaction(viewerId, "You", null, it) }))
        }
    }
    private suspend fun refresh() {
        val comments = source.comments(titleId)
        ensureCurrent()
        val reactions = source.reactions(titleId)
        ensureCurrent()
        mutable.value = state.value.copy(comments = comments, reactions = reactions)
    }
    private suspend fun ensureCurrent() {
        coroutineContext.ensureActive()
        if (!active()) throw CancellationException("Social screen closed")
    }
    private fun run(loading: Boolean = false, action: suspend () -> Unit) {
        if (!active() || state.value.busy) return
        mutable.value = state.value.copy(busy = true, loading = loading, error = null)
        job = scope.launch {
            try { ensureCurrent(); action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(error = e.message ?: "Could not update discussion.") }
            finally {
                if (active()) mutable.value = state.value.copy(busy = false, loading = false)
                else mutable.value = TitleDiscussionState(loading = false)
            }
        }
    }
    fun close() { closed = true; job?.cancel(); mutable.value = TitleDiscussionState(loading = false) }
}

data class RecommendationPickerState(
    val friends: List<Friendship> = emptyList(),
    val sent: Set<String> = emptySet(),
    val sending: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
)

class RecommendationController(
    private val source: TitleSocialSource,
    private val viewerId: String,
    private val draft: RecommendationDraft,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(RecommendationPickerState())
    val state = mutable.asStateFlow()
    private val jobs = mutableListOf<Job>()
    private var closed = false
    private fun active() = !closed && viewerId.isNotBlank() && source.isActive()
    private suspend fun ensureCurrent() {
        coroutineContext.ensureActive()
        if (!active()) throw CancellationException("Recommendation screen closed")
    }
    fun load() {
        if (!active() || jobs.any { it.isActive }) return
        mutable.value = state.value.copy(loading = true, error = null)
        jobs += scope.launch {
            try {
                val friends = source.friends().filter { it.relationFor(viewerId) == FriendshipRelation.FRIENDS }
                ensureCurrent()
                val sent = source.sent(draft.tmdbId, draft.type)
                ensureCurrent()
                mutable.value = state.value.copy(friends = friends, sent = sent.keys)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(error = e.message ?: "Could not load friends.") }
            finally { if (active()) mutable.value = state.value.copy(loading = false) else clear() }
        }
    }
    fun send(recipientId: String, note: String, url: String) {
        if (!active() || state.value.loading || recipientId in state.value.sending || state.value.friends.none { it.friendUserId == recipientId }) return
        mutable.value = state.value.copy(sending = state.value.sending + recipientId, errors = state.value.errors - recipientId)
        jobs += scope.launch {
            try {
                ensureCurrent()
                source.recommend(recipientId, draft, note.take(280), url)
                ensureCurrent()
                mutable.value = state.value.copy(sent = state.value.sent + recipientId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (active()) mutable.value = state.value.copy(errors = state.value.errors + (recipientId to (e.message ?: "Could not send.")))
            } finally { if (active()) mutable.value = state.value.copy(sending = state.value.sending - recipientId) else clear() }
        }
    }
    private fun clear() { mutable.value = RecommendationPickerState(loading = false) }
    fun close() { closed = true; jobs.forEach { it.cancel() }; jobs.clear(); clear() }
}
