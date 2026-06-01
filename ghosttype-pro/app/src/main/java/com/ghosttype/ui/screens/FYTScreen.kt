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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttype.ime.AutoTypeEngine
import com.ghosttype.utils.SettingsStore

private val Orange = Color(0xFFFF8C00)
private val Green = Color(0xFF4CAF50)

@Composable
fun FYTScreen() {
    val ctx = LocalContext.current
    val prefs = SettingsStore.prefs(ctx)

    var count by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_FYT_COUNT, 3)) }
    var wordsText by remember { mutableStateOf(prefs.getString(SettingsStore.KEY_FYT_WORDS, "") ?: "") }

    var imeTick by remember { mutableStateOf(0) }
    val imeReady = remember(imeTick) { AutoTypeEngine.injector != null }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); imeTick++ } }

    val words = remember(wordsText) {
        wordsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("FYT Type", color = Orange, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)

        // IME status
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (imeReady) Green.copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (imeReady) Green else Color(0xFFFF5252)))
            Text(
                if (imeReady) "Keyboard active — ready to type" else "Open GhostType keyboard first",
                color = if (imeReady) Green else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        }

        // Count setter
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Type Count", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Each word will type this many times", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { count = (count - 1).coerceAtLeast(1); prefs.edit().putInt(SettingsStore.KEY_FYT_COUNT, count).apply() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp)
                    ) { Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

                    Text(
                        "$count",
                        color = Orange,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp,
                        modifier = Modifier.widthIn(min = 60.dp),
                        textAlign = TextAlign.Center
                    )

                    OutlinedButton(
                        onClick = { count = (count + 1).coerceAtMost(50); prefs.edit().putInt(SettingsStore.KEY_FYT_COUNT, count).apply() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp)
                    ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // Words input
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your Words", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("One word per line", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                OutlinedTextField(
                    value = wordsText,
                    onValueChange = {
                        wordsText = it
                        prefs.edit().putString(SettingsStore.KEY_FYT_WORDS, it).apply()
                    },
                    placeholder = { Text("hello\nthanks\ngoodbye", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
                    minLines = 4, maxLines = 8,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (words.isNotEmpty()) {
                    Text("${words.size} word(s) loaded", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Word buttons
        if (words.isNotEmpty()) {
            Text("Tap a word to type it $count times", color = Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp)

            words.chunked(2).forEach { rowWords ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowWords.forEach { word ->
                        Button(
                            onClick = {
                                val inj = AutoTypeEngine.injector
                                if (inj != null) {
                                    repeat(count) {
                                        inj(word)
                                        if (it < count - 1) inj("\n")
                                    }
                                }
                            },
                            enabled = imeReady,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Orange,
                                contentColor = Color.Black,
                                disabledContainerColor = Orange.copy(alpha = 0.3f),
                                disabledContentColor = Color.Black.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Text(word, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}
