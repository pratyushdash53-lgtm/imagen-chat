package app.imagen.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.imagen.chat.ChatViewModel
import app.imagen.chat.data.AppSettings
import app.imagen.chat.net.ImageApi

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val saved = vm.settings
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var quality by remember { mutableStateOf(saved.quality) }
    var contextModel by remember { mutableStateOf(saved.contextModel) }
    var transparentDefault by remember { mutableStateOf(saved.transparentDefault) }
    var showKey by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val shown = remember(vm.allModels, showAll) {
        if (showAll) vm.allModels else vm.allModels.filter { ImageApi.isCandidate(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                vm.saveSettings(AppSettings(baseUrl.trim(), apiKey.trim(), model, quality, transparentDefault, contextModel))
                vm.transparent = transparentDefault
                onBack()
            }) { Text("Save") }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("Provider")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Any OpenAI-compatible endpoint works. The key is stored encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Model")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { vm.loadModels(baseUrl, apiKey.trim()) },
                    enabled = !vm.modelsLoading,
                ) { Text("Find models") }
                if (vm.modelsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            vm.modelsError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (vm.allModels.isNotEmpty()) {
                val auto = ImageApi.chooseAuto(vm.allModels)
                Text(
                    "${shown.size} image model(s) found" + (auto?.let { " · Auto picks $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelRow("Auto (recommended)", model == "auto") { model = "auto" }
            shown.forEach { id -> ModelRow(id, model == id) { model = id } }
            if (model != "auto" && model !in shown) ModelRow(model, true) { }
            if (vm.allModels.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show all models", modifier = Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Chat context")
            Text(
                "A text model reads the conversation so follow-ups like \u201Cremove the outline\u201D edit the " +
                    "previous image instead of starting over. Auto uses ${ImageApi.CONTEXT_PREFERENCE} when your " +
                    "provider has it. Tap Find models above to list the others.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chatModels = remember(vm.allModels) { vm.allModels.filter { ImageApi.isChatModel(it) } }
            if (vm.allModels.isNotEmpty()) {
                Text(
                    "Auto picks " + (ImageApi.chooseContext(vm.allModels) ?: "nothing (no text model listed)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelRow("Auto (recommended)", contextModel == "auto") { contextModel = "auto" }
            ModelRow("Off (each message stands alone)", contextModel == "off") { contextModel = "off" }
            chatModels.forEach { id -> ModelRow(id, contextModel == id) { contextModel = id } }
            if (contextModel != "auto" && contextModel != "off" && contextModel !in chatModels) {
                ModelRow(contextModel, true) { }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Image")
            Text("Quality", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto", "low", "medium", "high").forEach { q ->
                    FilterChip(
                        selected = quality == q,
                        onClick = { quality = q },
                        label = { Text(q.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Transparent by default")
                    Text(
                        "Asks for a transparent PNG and cuts out plain backdrops locally if needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = transparentDefault, onCheckedChange = { transparentDefault = it })
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.saveSettings(AppSettings(baseUrl.trim(), apiKey.trim(), model, quality, transparentDefault, contextModel))
                    vm.transparent = transparentDefault
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
            }
        }
    }
}
