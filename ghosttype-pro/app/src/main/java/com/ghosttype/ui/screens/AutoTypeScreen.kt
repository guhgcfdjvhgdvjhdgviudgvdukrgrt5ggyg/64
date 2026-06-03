package com.ghosttype.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttype.ime.AutoTypeEngine
import com.ghosttype.utils.SettingsStore

private val Orange = Color(0xFFFF8C00)

@Composable
fun AutoTypeScreen(
    onPickTxt: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val ctx       = LocalContext.current
    val prefs     = SettingsStore.prefs(ctx)
    val state     by AutoTypeEngine.state.collectAsState()

    var targetName by remember { mutableStateOf(prefs.getString(SettingsStore.KEY_AT_TARGET_NAME, "") ?: "") }
    var customText by remember { mutableStateOf(prefs.getString(SettingsStore.KEY_AT_CUSTOM_TEXT, "") ?: "") }
    var delay      by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_AT_DELAY, 5)) }
    var charDelay  by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_AT_CHAR_DELAY_MS, 35)) }
    var loop       by remember { mutableStateOf(prefs.getBoolean(SettingsStore.KEY_AT_LOOP, false)) }
    var autoSend   by remember { mutableStateOf(prefs.getBoolean(SettingsStore.KEY_AT_AUTO_SEND, true)) }

    var imeTick by remember { mutableStateOf(0) }
    val imeReady = remember(imeTick, state.running) { AutoTypeEngine.isImeReady }
    LaunchedEffect(Unit) { while (isActive) { kotlinx.coroutines.delay(800); imeTick++ } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Auto-Type", color = Orange, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)

        // ── IME status banner ─────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (imeReady) Color(0xFF4CAF50).copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (imeReady) Color(0xFF4CAF50) else Color(0xFFFF5252))
            )
            Text(
                if (imeReady) "Keyboard active — ready" else "GhostType keyboard not active",
                color = if (imeReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }

        // ══════════════════════════════════════════════════════
        // CARD 1 — Target Name + Messages
        // ══════════════════════════════════════════════════════
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Message Setup", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                OutlinedButton(
                    onClick = onPickTxt,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("Load .txt", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }

            val loadedName = state.sourceName.ifBlank { null }
            if (loadedName != null) {
                Text("📄  $loadedName  ·  ${state.total} lines", color = Color(0xFF4CAF50), fontSize = 12.sp)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // Target Name
            Text("Target Name", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 0.6.sp)
            OutlinedTextField(
                value = targetName,
                onValueChange = {
                    targetName = it
                    prefs.edit().putString(SettingsStore.KEY_AT_TARGET_NAME, it).apply()
                },
                placeholder = { Text("e.g. Ali (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Messages
            Text("Messages", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 0.6.sp)
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                placeholder = { Text("One message per line", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
                minLines = 4, maxLines = 10,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        prefs.edit().putString(SettingsStore.KEY_AT_CUSTOM_TEXT, customText).apply()
                        AutoTypeEngine.loadFromText(ctx, customText, "Custom text")
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("Use this text", fontWeight = FontWeight.Bold, fontSize = 13.sp) }

                OutlinedButton(
                    onClick = {
                        customText = ""
                        AutoTypeEngine.clearMessages(ctx)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(42.dp)
                ) { Text("Clear", fontSize = 13.sp) }
            }
        }

        // ══════════════════════════════════════════════════════
        // SETTINGS
        // ══════════════════════════════════════════════════════
        SectionCard {
            Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Message delay", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconBtn("-") { delay = (delay - 1).coerceAtLeast(1); prefs.edit().putInt(SettingsStore.KEY_AT_DELAY, delay).apply() }
                    Text("${delay}s", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.widthIn(min = 28.dp), textAlign = TextAlign.Center)
                    IconBtn("+") { delay = (delay + 1).coerceAtMost(60); prefs.edit().putInt(SettingsStore.KEY_AT_DELAY, delay).apply() }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Typing speed", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text("${charDelay}ms/char", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Slider(
                value = charDelay.toFloat(), valueRange = 0f..200f,
                onValueChange = { charDelay = it.toInt(); prefs.edit().putInt(SettingsStore.KEY_AT_CHAR_DELAY_MS, charDelay).apply() },
                colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            ToggleRow("Auto-send after each line", autoSend) { autoSend = it; prefs.edit().putBoolean(SettingsStore.KEY_AT_AUTO_SEND, it).apply() }
            ToggleRow("Loop mode", loop) { loop = it; prefs.edit().putBoolean(SettingsStore.KEY_AT_LOOP, it).apply() }
        }

        // ══════════════════════════════════════════════════════
        // CARD 2 — Auto-Type Controls
        // ══════════════════════════════════════════════════════
        SectionCard {
            val total = state.total.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Message ${state.current} of ${state.total}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (state.running) {
                    Text(
                        if (state.paused) "PAUSED" else "RUNNING",
                        color = if (state.paused) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF4CAF50),
                        fontWeight = FontWeight.ExtraBold, fontSize = 11.sp
                    )
                }
            }
            LinearProgressIndicator(
                progress = { (state.current.toFloat() / total).coerceIn(0f, 1f) },
                color = Orange,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            )
            if (state.currentLine.isNotEmpty()) {
                Text("> ${state.currentLine}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !state.running,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange, contentColor = Color.Black,
                        disabledContainerColor = Orange.copy(alpha = 0.35f),
                        disabledContentColor = Color.Black.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("▶  Start", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }

                OutlinedButton(
                    onClick = if (state.paused) onResume else onPause,
                    enabled = state.running,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp,
                        if (state.running) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text(if (state.paused) "▶  Resume" else "⏸  Pause", fontWeight = FontWeight.Bold, fontSize = 14.sp) }

                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("■  Stop", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }

            state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun IconBtn(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(32.dp)
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Switch(
            checked = checked, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Orange)
        )
    }
}
