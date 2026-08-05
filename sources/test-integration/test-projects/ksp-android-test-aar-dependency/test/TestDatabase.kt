import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

// A Room database declared in the test fragment only, so that the Room runtime (an AAR on Android)
// is only present in the test dependencies.
@Database(entities = [TodoEntity::class], version = 1)
@ConstructedBy(TestDatabaseConstructor::class)
abstract class TestDatabase : RoomDatabase() {
    abstract fun getDao(): TodoDao
}

// The Room compiler generates the `actual` implementations.
@Suppress("KotlinNoActualForExpect")
expect object TestDatabaseConstructor : RoomDatabaseConstructor<TestDatabase> {
    override fun initialize(): TestDatabase
}

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(item: TodoEntity)

    @Query("SELECT count(*) FROM TodoEntity")
    suspend fun count(): Int
}

@Entity
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
)
