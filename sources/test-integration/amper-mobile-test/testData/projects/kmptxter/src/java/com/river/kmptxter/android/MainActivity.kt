package java.com.river.kmptxter.android

import FileViewerScreen
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme { MyApp() }

        }
    }
}

@Composable
fun MyApp() {
    val navController = rememberNavController()
    val history = remember { mutableStateListOf<Uri>() }

    NavHost(navController = navController, startDestination = "filePicker") {
        composable("filePicker") {
            FilePickerScreen(navController, history)
        }
        composable("fileViewer/{uri}") { backStackEntry ->
            val uri = backStackEntry.arguments?.getString("uri")?.let { Uri.parse(it) }
            if (uri != null) {
                FileViewerScreen(uri)
            }
        }
    }
}
