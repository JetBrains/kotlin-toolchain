package {{PROJECT_ID}}

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "{{PROJECT_NAME}}") {
        App()
    }
}
