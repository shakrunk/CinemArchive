package work.kumarfamilynet.cinemarchive.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OutingRecoveryArchiveTest {
    @Test fun originalAndAttemptSurviveReopenAndStayInTheOwnersSeparateStore() = runBlocking {
        val aFile = File.createTempFile("ora", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
        val bFile = File.createTempFile("orb", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
        val firstJob = Job()
        val a = DataStoreOutingRecoveryArchive(PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO)) { aFile })
        val original = """{"version":1,"state":"pending","original":{"payloadJson":"exact saved payload"},"attempt":{"operationId":"stable","expectedVersion":"version","patch":{"venue":"Saved"}}}"""
        a.put("entry", original)
        assertEquals(original, a.records.first()["entry"])
        firstJob.cancelAndJoin()

        val secondJob = Job()
        try {
            val reopened = DataStoreOutingRecoveryArchive(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO)) { aFile })
            val other = DataStoreOutingRecoveryArchive(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO)) { bFile })
            assertEquals(original, reopened.records.first()["entry"])
            assertTrue(other.records.first().isEmpty())
        } finally {
            secondJob.cancelAndJoin()
            aFile.delete(); bFile.delete()
        }
    }
}

