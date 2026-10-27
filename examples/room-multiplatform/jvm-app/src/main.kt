import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    val dao = getRoomDatabase(getDatabaseBuilder()).getDao()
    application {
        Window(onCloseRequest = ::exitApplication) {
            Screen(dao)
        }
    }
}
