package work.kumarfamilynet.cinemarchive.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library_titles")
data class LibraryTitleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val status: String,
)

@Dao
interface LibraryTitleDao {
    @Query("SELECT * FROM library_titles ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LibraryTitleEntity>>
}

@Database(entities = [LibraryTitleEntity::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryTitleDao(): LibraryTitleDao

    companion object {
        fun create(context: Context): LibraryDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            "cinemarchive.db",
        ).build()
    }
}
