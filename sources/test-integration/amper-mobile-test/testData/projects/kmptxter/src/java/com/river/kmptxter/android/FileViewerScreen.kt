

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.river.kmptxter.android.R
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun FileViewerScreen(uri: Uri) {
    val context = LocalContext.current
    val initialText = "Loading"
    val fileContent = remember { mutableStateOf(initialText) }
    LaunchedEffect(uri) {
        fileContent.value = readTextFromUri(context, uri)
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier

            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(text = fileContent.value, color = Color.White)
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    val contentResolver = context.contentResolver
    return contentResolver.openInputStream(uri)?.use { inputStream ->
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.readText()
        }
    } ?: "Error"
}
