package work.kumarfamilynet.cinemarchive.core.model

/**
 * A user-created custom list of titles (see schema.sql `lists`). Named `TitleList`, not
 * `List`, to avoid colliding with `kotlin.collections.List` everywhere this is used.
 * Private-only — no sharing yet, mirrors the web app's `List` type in `mockData.ts`.
 */
data class TitleList(
    val id: String,
    val name: String,
    val description: String?,
    val createdAt: String,
    val updatedAt: String,
)
