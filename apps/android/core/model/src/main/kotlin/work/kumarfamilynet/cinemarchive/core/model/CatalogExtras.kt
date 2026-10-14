package work.kumarfamilynet.cinemarchive.core.model

import java.util.Locale

data class CatalogExtrasKey(val tmdbId: Int, val type: MediaType, val region: String)
data class CatalogVideo(val key: String, val name: String, val type: String, val official: Boolean) {
    init { require(Regex("[A-Za-z0-9_-]{11}").matches(key)) }
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$key"
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$key/hqdefault.jpg"
}
data class CatalogProvider(val id: Int, val name: String, val logoUrl: String?)
data class CatalogProviders(
    val link: String?,
    val stream: List<CatalogProvider>,
    val rent: List<CatalogProvider>,
    val buy: List<CatalogProvider>,
) {
    val isEmpty: Boolean get() = stream.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

/** Same language-tag selection as the web media catalog; missing regions fall back to US. */
fun catalogWatchRegion(languageTag: String): String =
    languageTag.split('-').getOrNull(1)?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "US"
