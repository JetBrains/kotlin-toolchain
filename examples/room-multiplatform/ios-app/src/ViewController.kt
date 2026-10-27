import androidx.compose.ui.window.ComposeUIViewController

fun ViewController(): platform.UIKit.UIViewController {
    val dao = getRoomDatabase(getDatabaseBuilder()).getDao()
    return ComposeUIViewController { Screen(dao) }
}
