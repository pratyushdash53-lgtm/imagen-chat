package app.imagen.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.imagen.chat.ui.ChatApp
import app.imagen.chat.ui.ImagenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImagenTheme {
                val vm: ChatViewModel = viewModel()
                ChatApp(vm)
            }
        }
    }
}
