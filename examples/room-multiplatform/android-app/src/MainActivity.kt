package hello.world

import Screen
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import getDatabaseBuilder
import getRoomDatabase

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dao = getRoomDatabase(getDatabaseBuilder(applicationContext)).getDao()
        setContent {
            Screen(dao)
        }
    }
}
