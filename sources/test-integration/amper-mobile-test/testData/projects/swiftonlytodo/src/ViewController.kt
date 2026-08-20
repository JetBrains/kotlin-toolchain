import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.mutableStateListOf

data class ToDoItem(val id: Int, val text: String, var isDone: Boolean)

class ToDoViewModel {
    private val _items = mutableStateListOf<ToDoItem>()
    val items: List<ToDoItem> get() = _items

    val progress: Float
        get() = if (_items.isEmpty()) 0f else _items.count { it.isDone }.toFloat() / _items.size

    fun addItem(text: String) {
        _items.add(ToDoItem(id = _items.size + 1, text = text, isDone = false))
    }

    fun toggleItemDone(id: Int) {
        val item = _items.find { it.id == id }
        if (item != null) item.isDone = !item.isDone
    }

    fun removeItem(id: Int) {
        _items.removeAll { it.id == id }
    }
}

@Composable
fun ToDoApp(viewModel: ToDoViewModel = ToDoViewModel()) {
    var newTaskText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Progress Bar
        Text("Progress")
        LinearProgressIndicator(
            progress = viewModel.progress,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        // Add Task Input
        TextField(
            value = newTaskText,
            onValueChange = { newTaskText = it },
            placeholder = { Text("Enter a new task") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (newTaskText.isNotBlank()) {
                    viewModel.addItem(newTaskText)
                    newTaskText = ""
                }
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Add Task")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Task List
        LazyColumn {
            items(viewModel.items.size) { index ->
                val item = viewModel.items[index]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.isDone,
                            onCheckedChange = { viewModel.toggleItemDone(item.id) }
                        )
                        IconButton(onClick = { viewModel.removeItem(item.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Task")
                        }
                    }
                }
            }
        }
    }
}

fun ViewController() = ComposeUIViewController { ToDoApp() }
