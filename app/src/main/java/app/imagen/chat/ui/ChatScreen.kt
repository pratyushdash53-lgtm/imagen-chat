package app.imagen.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.imagen.chat.ChatViewModel
import app.imagen.chat.data.ChatMessage
import app.imagen.chat.util.ImageUtils

private val Suggestions = listOf(
    "A cozy fox mascot, flat vector style",
    "Pixel-art treasure chest, 16 colours",
    "Glossy rocket app icon, soft shadow",
    "Watercolor monstera leaf",
)

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onMenu: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val itemCount = vm.messages.size + if (vm.generating) 1 else 0

    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TopBar(
            title = vm.title,
            subtitle = if (vm.settings.model == "auto") "Auto model" else vm.settings.model,
            onMenu = onMenu,
            onNew = { vm.newChat() },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (itemCount == 0) {
                EmptyState(onPick = { draft = it })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(vm.messages, key = { it.id }) { message ->
                        MessageItem(
                            vm = vm,
                            message = message,
                            onOpen = { viewing = message },
                        )
                    }
                    if (vm.generating) {
                        item(key = "generating") {
                            val ratio = vm.generatingAspect.ratio
                            GeneratingCard(
                                stage = vm.stage,
                                ratio = ratio,
                                onCancel = { vm.cancelGeneration() },
                                modifier = Modifier
                                    .fillMaxWidth(if (ratio < 0.9f) 0.7f else 0.92f)
                                    .aspectRatio(ratio),
                            )
                        }
                    }
                }
            }
        }

        Composer(
            text = draft,
            onText = { draft = it },
            transparent = vm.transparent,
            onTransparent = { vm.transparent = it },
            aspectLabel = vm.aspect.label,
            onAspect = { vm.aspect = vm.aspect.next() },
            generating = vm.generating,
            onSend = {
                val ok = vm.send(draft)
                if (ok) draft = "" else onOpenSettings()
            },
            onCancel = { vm.cancelGeneration() },
        )
    }

    viewing?.let { message ->
        Viewer(vm = vm, message = message, onDismiss = { viewing = null })
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, onMenu: () -> Unit, onNew: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.Menu, contentDescription = "History")
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onNew) {
            Icon(Icons.Default.Add, contentDescription = "New chat")
        }
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFFFF6FB5))),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("✦", color = Color.White, fontSize = 28.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("What shall we create?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Describe an image. Switch on Transparent for clean cut-out PNGs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Suggestions.forEach { text ->
            Surface(
                onClick = { onPick(text) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MessageItem(vm: ChatViewModel, message: ChatMessage, onOpen: () -> Unit) {
    val context = LocalContext.current
    if (message.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (message.error != null) {
            Surface(
                shape = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(message.error, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (message.error != "Generation cancelled.") {
                        TextButton(onClick = { vm.retry(message) }) { Text("Retry") }
                    }
                }
            }
            return@Column
        }
        val file = message.imageFile?.let { vm.imageFile(it) } ?: return@Column
        val ratio = if (message.height > 0) message.width.toFloat() / message.height else 1f
        PictureBox(
            file = file,
            hasAlpha = message.hasAlpha,
            ratio = ratio,
            modifier = Modifier
                .fillMaxWidth(if (ratio < 0.9f) 0.7f else 0.92f)
                .aspectRatio(ratio)
                .clickable(onClick = onOpen),
        )
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallPill("${message.width}×${message.height}")
            if (message.hasAlpha) SmallPill("Transparent")
            if (message.backgroundRemoved) SmallPill("Cut out locally")
        }
        message.warning?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 340.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val saved = ImageUtils.saveToGallery(context, file)
                vm.notice = if (saved) "Saved to Pictures/ImagenChat" else "Couldn't save the image"
            }) { Text("Save") }
            TextButton(onClick = { ImageUtils.share(context, file) }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Share")
            }
        }
    }
}

@Composable
private fun Viewer(vm: ChatViewModel, message: ChatMessage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val file = message.imageFile?.let { vm.imageFile(it) } ?: return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            PictureBox(
                file = file,
                hasAlpha = message.hasAlpha,
                ratio = 1f,
                corner = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .aspectRatio(
                        if (message.height > 0) message.width.toFloat() / message.height else 1f,
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = {
                    val saved = ImageUtils.saveToGallery(context, file)
                    vm.notice = if (saved) "Saved to Pictures/ImagenChat" else "Couldn't save the image"
                }) { Text("Save", color = Color.White) }
                TextButton(onClick = { ImageUtils.share(context, file) }) { Text("Share", color = Color.White) }
                TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    transparent: Boolean,
    onTransparent: (Boolean) -> Unit,
    aspectLabel: String,
    onAspect: () -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            TextField(
                value = text,
                onValueChange = onText,
                placeholder = { Text("Describe an image…") },
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(
                Modifier.padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = transparent,
                    onClick = { onTransparent(!transparent) },
                    label = { Text("Transparent") },
                    leadingIcon = if (transparent) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
                AssistChip(onClick = onAspect, label = { Text(aspectLabel) })
                Spacer(Modifier.weight(1f))
                val canSend = generating || text.isNotBlank()
                Box(
                    Modifier
                        .size(44.dp)
                        .alpha(if (canSend) 1f else 0.35f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = canSend) { if (generating) onCancel() else onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (generating) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (generating) "Cancel" else "Generate",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
