package app.imagen.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.imagen.chat.ui.ChatApp
import app.imagen.chat.ui.ImagenTheme

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            ImagenTheme {
                ChatApp(vm)
            }
        }
    }

    // Tapping a "finished" notification brings the existing activity forward on that chat.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_CONVERSATION) ?: return
        vm.openConversation(id)
        intent.removeExtra(EXTRA_CONVERSATION)
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation_id"
    }
}
