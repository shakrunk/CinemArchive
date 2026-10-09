package work.kumarfamilynet.cinemarchive.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Recreate the complete historical schema before seeding migration data. */
internal fun restoreEmptyFixtureSchema(db: SupportSQLiteDatabase, schema: JSONObject) {
    // Start from the complete historical schema, never a mix of historical and current tables.
    val tables = mutableListOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'").use {
        while (it.moveToNext()) tables += it.getString(0)
    }
    // Drop children before parents. The legacy Robolectric driver may retain FK checks
    // even after setForeignKeyConstraintsEnabled(false).
    val parents = tables.associateWith { table ->
        buildSet {
            db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table")))
            }
        }
    }.toMutableMap()
    while (parents.isNotEmpty()) {
        val child = parents.keys.firstOrNull { candidate -> parents.values.none { candidate in it } }
            ?: error("Unexpected foreign-key cycle in empty migration fixture")
        db.execSQL("DROP TABLE `$child`")
        parents.remove(child)
    }
    val entities = schema.getJSONArray("entities")
    for (index in 0 until entities.length()) {
        val entity = entities.getJSONObject(index)
        fun String.sql() = replace("\${TABLE_NAME}", entity.getString("tableName"))
        db.execSQL(entity.getString("createSql").sql())
        entity.optJSONArray("indices")?.let { indices ->
            for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").sql())
        }
    }
    val setup = schema.getJSONArray("setupQueries")
    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
}
