package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal data class PendingProviderLink(val link: ProviderTitleLink, val titleId: String,
    val tmdbId: Int? = null, val type: String? = null)

/** Reviewed and unknown-outcome commands retain their immutable provider identity. */
internal fun pendingProviderLinks(queue: List<OutboxEntity>, owner: TicketOwnerScope): List<PendingProviderLink> =
    queue.flatMap { entry ->
        when {
            isBackupImport(entry) -> checkedImportCommand(entry, owner).let { command ->
                command.providerLinks.map { PendingProviderLink(it, entry.entityId,
                    command.title.getInt("tmdbId"), command.title.getString("type")) }
            }
            entry.entityType == PROVIDER_MERGE -> {
                val command = checkedProviderMerge(entry, owner)
                val link = checkedProviderLinks(JSONArray().put(exactMetadataObject(entry.payloadJson).getJSONObject("link"))).single()
                listOf(PendingProviderLink(link, command.titleId))
            }
            else -> emptyList()
        }
    }

internal fun requirePendingProviderTarget(links: List<PendingProviderLink>, link: ProviderTitleLink, titleId: String) {
    require(links.none { it.link == link && it.titleId != titleId }) {
        "This provider identity already belongs to another saved title. Review its pending import first."
    }
}
