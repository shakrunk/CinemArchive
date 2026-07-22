package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleListRow

private class RecordingTitleDao : TitleDao {
    data class Call(val titleId: String, val status: String, val updatedAt: String)

    val calls = mutableListOf<Call>()

    override fun observeLibrary(): Flow<List<TitleListRow>> = flowOf(emptyList())
    override fun observeTitle(titleId: String): Flow<TitleEntity?> = flowOf(null)
    override fun observeAllTitles(): Flow<List<TitleEntity>> = flowOf(emptyList())
    override suspend fun upsertAll(titles: List<TitleEntity>) = Unit
    override suspend fun count(): Int = 0

    override suspend fun updateStatus(titleId: String, status: String, updatedAt: String) {
        calls += Call(titleId, status, updatedAt)
    }

    override suspend fun updateRating(titleId: String, rating: Double, updatedAt: String) = Unit
}

class TitleConflictHandlerTest {

    @Test
    fun `applies the server-winning title row locally`() = runTest {
        val dao = RecordingTitleDao()
        val handler = TitleConflictHandler(dao)

        handler.applyRemote(
            "title",
            "title-1",
            JSONObject().apply {
                put("status", "DROPPED")
                put("updatedAt", "2026-07-14T00:05:00.000Z")
            },
        )

        assertEquals(1, dao.calls.size)
        assertEquals(RecordingTitleDao.Call("title-1", "DROPPED", "2026-07-14T00:05:00.000Z"), dao.calls.single())
    }

    @Test
    fun `ignores entity types that never produce a conflict`() = runTest {
        val dao = RecordingTitleDao()
        val handler = TitleConflictHandler(dao)

        handler.applyRemote("episode_watch_event", "event-1", JSONObject())

        assertEquals(0, dao.calls.size)
    }
}
