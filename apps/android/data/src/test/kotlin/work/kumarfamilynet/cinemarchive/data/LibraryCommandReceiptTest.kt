package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryCommandReceiptTest {
    @Test fun receiptValidationRejectsMissingForeignMismatchedAndReusedIdentities() {
        val operationId=UUID.randomUUID().toString(); val owner=UUID.randomUUID().toString(); val parent=UUID.randomUUID().toString()
        val operations=JSONArray((1..2).map { person -> JSONObject().put("table","season_cast").put("action","put")
            .put("key",JSONObject().put("season_id",parent).put("tmdb_person_id",person))
            .put("values",JSONObject().put("title_id","title")) })
        val original=JSONObject().put("operationId",operationId).put("rows",JSONArray((0..1).map { i ->
            val key=operations.getJSONObject(i).getJSONObject("key")
            JSONObject().put("table","season_cast").put("key",key).put("row",JSONObject(key.toString())
                .put("id",UUID.randomUUID().toString()).put("title_id","title").put("user_id",owner))
        }))
        assertEquals(2,checkedLibraryCommandReceipt(operationId,operations,original,owner).size)
        val corruptions:List<(JSONObject)->Unit> = listOf(
            { it.put("operationId",UUID.randomUUID().toString()) },
            { it.put("rows",JSONArray()) },
            { it.getJSONArray("rows").getJSONObject(0).put("table","title_cast") },
            { it.getJSONArray("rows").getJSONObject(0).getJSONObject("key").put("tmdb_person_id",42) },
            { it.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("user_id","other") },
            { it.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("title_id","other") },
            { it.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("season_id","other") },
            { it.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("id","1-1-1-1-1") },
            { it.getJSONArray("rows").getJSONObject(1).getJSONObject("row").put("id",it.getJSONArray("rows").getJSONObject(0).getJSONObject("row").getString("id")) },
            { it.getJSONArray("rows").getJSONObject(0).put("deleted",true) },
        )
        corruptions.forEachIndexed { index, mutate ->
            val corrupt=JSONObject(original.toString()); mutate(corrupt)
            assertTrue("corruption $index",runCatching { checkedLibraryCommandReceipt(operationId,operations,corrupt,owner) }.isFailure)
        }
    }
}
