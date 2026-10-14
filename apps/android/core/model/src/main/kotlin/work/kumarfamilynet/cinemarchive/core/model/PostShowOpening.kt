package work.kumarfamilynet.cinemarchive.core.model

/** Immutable account-scoped admission evidence captured before the post-show form opens. */
data class PostShowOpening(val viewing: ViewingDraft, val reversalContext: String? = null)
