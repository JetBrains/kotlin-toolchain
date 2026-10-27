import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun Screen(dao: TodoDao) {
    val todos by remember(dao) { dao.getAllAsFlow() }.collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Todos on ${getWorld()}", style = MaterialTheme.typography.titleLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("New todo") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val newTitle = title.trim()
                        title = ""
                        scope.launch { dao.insert(TodoEntity(title = newTitle, content = "")) }
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text("Add")
                }
            }
            LazyColumn {
                items(todos, key = { it.id }) { todo ->
                    Text("• ${todo.title}", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
