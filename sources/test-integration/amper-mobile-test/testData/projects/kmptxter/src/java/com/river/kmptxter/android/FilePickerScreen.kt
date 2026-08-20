package java.com.river.kmptxter.android

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(navController: NavController, history: MutableList<Uri>) {

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            history.add(it)
            navController.navigate("fileViewer/${Uri.encode(it.toString())}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History", fontSize = 20.sp) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    launcher.launch(arrayOf("text/plain"))
                },
                containerColor = MaterialTheme.colorScheme.primary, // Использование основного цвета темы
                contentColor = MaterialTheme.colorScheme.onPrimary  // Цвет текста и иконок
            ) {
                Text("+", fontSize = 24.sp)
            }
        },
        containerColor = MaterialTheme.colorScheme.background, // Цвет фона для Scaffold
        content = { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background) // Цвет фона для LazyColumn
                    .padding(16.dp)
            ) {
                items(history.size) { index ->
                    val uri = history[index]
                    val fileName = uri.lastPathSegment?.split("/")?.lastOrNull()
                        ?: "Unknown file"
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate("fileViewer/${Uri.encode(uri.toString())}")
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text(
                            text = fileName,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    )
}
