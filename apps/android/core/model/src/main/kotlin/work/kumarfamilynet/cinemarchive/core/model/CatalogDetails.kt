package work.kumarfamilynet.cinemarchive.core.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CatalogDetailRow(val label: String, val value: String)

/** Web Details semantics: release dates are calendar days; addedAt is a local-zone instant. */
fun TitleDetail.catalogDetailRows(
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    zone: ZoneId = ZoneId.systemDefault(),
): List<CatalogDetailRow> = buildList {
    network?.takeIf { it.isNotBlank() }?.let { add(CatalogDetailRow("Network", it)) }
    runtime?.takeIf { it > 0 }?.let { add(CatalogDetailRow("Runtime", "$it min")) }
    originalLanguage?.takeIf { it.isNotBlank() }?.let { code ->
        val language = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        add(CatalogDetailRow("Language", language.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code.uppercase(Locale.ROOT)))
    }
    releaseDate?.takeIf { it.isNotBlank() }?.let { date ->
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
        add(CatalogDetailRow(if (parsed != null && parsed > today) "Releases" else "Released", parsed?.format(catalogDateFormat) ?: date))
    }
    studios.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { add(CatalogDetailRow("Studio", it.joinToString(", "))) }
    collectionName?.replace(Regex("\\s+Collection$", RegexOption.IGNORE_CASE), "")?.takeIf { it.isNotBlank() }
        ?.let { add(CatalogDetailRow("Franchise", it)) }
    addedAt?.takeIf { it.isNotBlank() }?.let { value ->
        val date = runCatching { LocalDate.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value).atZone(zone).toLocalDate() }.getOrNull()
        add(CatalogDetailRow("Added", date?.format(catalogDateFormat) ?: value))
    }
    imdbRating?.takeIf { it.isFinite() }?.let {
        add(CatalogDetailRow("IMDb", "${BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()}/10"))
    }
}

private val catalogDateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
