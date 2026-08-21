package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.ListDao
import work.kumarfamilynet.cinemarchive.core.database.ListEntity
import work.kumarfamilynet.cinemarchive.core.database.ListItemDao
import work.kumarfamilynet.cinemarchive.core.database.ListItemEntity
import work.kumarfamilynet.cinemarchive.core.model.TitleList

/**
 * Owns [TitleList] CRUD and title membership — Room-first, outbox-backed, same optimistic
 * contract as [LibraryRepository.updateTitleStatus]: local write first, then enqueue for a
 * remote push. Private-only (no sharing yet), matching the web app's Lists feature.
 */
class ListsRepository(
    private val listDao: ListDao,
    private val listItemDao: ListItemDao,
    private val outbox: MutationOutbox,
) {
    fun observeLists(): Flow<List<TitleList>> = listDao.observeAllLists().map { rows -> rows.map { it.toDomain() } }

    fun observeList(id: String): Flow<TitleList?> = listDao.observeList(id).map { it?.toDomain() }

    fun observeItemsForList(listId: String): Flow<List<ListItemEntity>> = listItemDao.observeItemsForList(listId)

    fun observeListIdsForTitle(titleId: String): Flow<List<String>> = listItemDao.observeListIdsForTitle(titleId)

    /** listId -> member titleIds, across every list at once — what a Lists grid/detail screen
     *  needs to resolve membership against [LibraryRepository.observeLibrary]'s title list,
     *  mirroring the web app's `listMemberships: Record<listId, Set<titleId>>` store shape. */
    fun observeMembershipsByList(): Flow<Map<String, Set<String>>> =
        listItemDao.observeAllListItems().map { rows -> rows.groupBy({ it.listId }, { it.titleId }).mapValues { it.value.toSet() } }

    suspend fun createList(name: String, description: String?): String {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        listDao.upsert(ListEntity(id = id, name = name, description = description, createdAt = now, updatedAt = now))
        outbox.enqueue(
            entityType = "list",
            entityId = id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", id)
                put("name", name)
                put("description", description ?: JSONObject.NULL)
                put("createdAt", now)
                put("updatedAt", now)
            },
        )
        return id
    }

    suspend fun renameList(id: String, name: String, description: String?) {
        val existing = listDao.getById(id) ?: return
        val now = Instant.now().toString()
        listDao.upsert(existing.copy(name = name, description = description, updatedAt = now))
        outbox.enqueue(
            entityType = "list",
            entityId = id,
            operation = "update",
            payload = JSONObject().apply {
                put("id", id)
                put("name", name)
                put("description", description ?: JSONObject.NULL)
                put("updatedAt", now)
            },
        )
    }

    suspend fun deleteList(id: String) {
        listDao.deleteById(id) // Room's FK ON DELETE CASCADE removes the local list_items rows too.
        outbox.enqueue(
            entityType = "list",
            entityId = id,
            operation = "delete",
            payload = JSONObject().put("id", id),
        )
    }

    /** Idempotent — a second call for an already-member title is a no-op, matching the
     *  server's unique(list_id, title_id) upsert contract. */
    suspend fun addTitleToList(listId: String, titleId: String) {
        if (listItemDao.findId(listId, titleId) != null) return
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        listItemDao.upsert(ListItemEntity(id = id, listId = listId, titleId = titleId, position = null, addedAt = now, updatedAt = now))
        outbox.enqueue(
            entityType = "list_item",
            entityId = id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", id)
                put("listId", listId)
                put("titleId", titleId)
                put("position", JSONObject.NULL)
                put("addedAt", now)
                put("updatedAt", now)
            },
        )
    }

    suspend fun removeTitleFromList(listId: String, titleId: String) {
        val id = listItemDao.findId(listId, titleId) ?: return
        listItemDao.deleteByListAndTitle(listId, titleId)
        outbox.enqueue(
            entityType = "list_item",
            entityId = id,
            operation = "delete",
            payload = JSONObject().put("id", id),
        )
    }
}

internal fun ListEntity.toDomain(): TitleList = TitleList(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
