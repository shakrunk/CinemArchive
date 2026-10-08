package work.kumarfamilynet.cinemarchive.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Recreate an empty table from its actual historical schema before seeding migration data. */
internal fun restoreEmptyFixtureTable(db: SupportSQLiteDatabase, schema: JSONObject, table: String) {
    db.execSQL("DROP TABLE `$table`")
    val entities = schema.getJSONArray("entities")
    val entity = (0 until entities.length()).map(entities::getJSONObject).single { it.getString("tableName") == table }
    fun String.sql() = replace("\${TABLE_NAME}", table)
    db.execSQL(entity.getString("createSql").sql())
    entity.optJSONArray("indices")?.let { indices ->
        for (index in 0 until indices.length()) db.execSQL(indices.getJSONObject(index).getString("createSql").sql())
    }
}
