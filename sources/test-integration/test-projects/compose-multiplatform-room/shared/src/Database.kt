import androidx.room3.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

// https://developer.android.com/kotlin/multiplatform/room#database-instantiation

fun getRoomDatabase(
    builder: RoomDatabase.Builder<AppDatabase>
): AppDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

// https://developer.android.com/kotlin/multiplatform/room#defining-database

@Database(entities = [TodoEntity::class], version = 1)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun getDao(): TodoDao
}

// The Room compiler generates the `actual` implementations.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(item: TodoEntity)

    @Query("SELECT count(*) FROM TodoEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM TodoEntity")
    fun getAllAsFlow(): Flow<List<TodoEntity>>
}

@Entity
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String
)
