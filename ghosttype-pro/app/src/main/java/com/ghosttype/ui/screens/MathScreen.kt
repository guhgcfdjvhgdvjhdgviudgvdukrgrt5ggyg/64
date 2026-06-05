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
import com.ghosttype.utils.SettingsStore
import com.ghosttype.utils.UnicodeFonts

private val Orange = Color(0xFFFF8C00)
private val Green  = Color(0xFF4CAF50)

@Composable
fun MathScreen() {
    val ctx   = LocalContext.current
    val prefs = SettingsStore.prefs(ctx)

    var mathEnabled by remember { mutableStateOf(prefs.getBoolean(SettingsStore.KEY_MATH_ENABLED, false)) }
    var mathCount   by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_MATH_COUNT, 1).coerceIn(1, 50)) }

    val exampleInput  = "GhostType Pro is BEST"
    val exampleOutput = UnicodeFonts.toMath(exampleInput)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            "Math Font Style",
            color = Orange,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // ── Math ON / OFF toggle ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.5.dp,
                    if (mathEnabled) Orange.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(14.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Math Mode",
                        color = if (mathEnabled) Orange else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        if (mathEnabled)
                            "ON — letters convert to 1337 numbers"
                        else
                            "OFF — types normally",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = mathEnabled,
                    onCheckedChange = {
                        mathEnabled = it
                        prefs.edit().putBoolean(SettingsStore.KEY_MATH_ENABLED, it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Orange
                    )
                )
            }
        }

        // ── Type Count ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Type Count",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Each word typed this many times",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            mathCount = (mathCount - 1).coerceAtLeast(1)
                            prefs.edit().putInt(SettingsStore.KEY_MATH_COUNT, mathCount).apply()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp)
                    ) { Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

                    Text(
                        "$mathCount",
                        color = Orange,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp,
                        modifier = Modifier.widthIn(min = 60.dp),
                        textAlign = TextAlign.Center
                    )

                    OutlinedButton(
                        onClick = {
                            mathCount = (mathCount + 1).coerceAtMost(50)
                            prefs.edit().putInt(SettingsStore.KEY_MATH_COUNT, mathCount).apply()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp)
                    ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // ── How to use ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                .border(1.5.dp, Orange.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How to use", color = Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "1. Turn Math Mode ON above\n" +
                    "2. Set Type Count (how many times each line types)\n" +
                    "3. Go to Auto-Type -> add messages -> Start\n" +
                    "4. Letters auto-convert to numbers while typing\n\n" +
                    "OR  ->  tap ≡ on keyboard -> Aa -> select Math style",
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp
                )
            }
        }

        // ── Letter → Number mapping ───────────────────────────────
        Text(
            "Letter -> Number Mapping",
            color = Orange,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp)
        )

        val mappings = listOf(
            "E" to "3", "T" to "7", "I" to "1", "O" to "0", "P" to "9",
            "A" to "4", "S" to "5", "G" to "6", "B" to "8"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            mappings.forEachIndexed { index, (letter, number) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (index % 2 == 0) Color(0xFF222222) else Color(0xFF1A1A1A),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(letter, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("->", color = Color(0xFF666666), fontSize = 16.sp)
                    Text(number, color = Orange, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Live example ──────────────────────────────────────────
        Text(
            "Example",
            color = Orange,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Before:", color = Color(0xFF888888), fontSize = 12.sp)
                Text(exampleInput, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Text("After (Math style):", color = Color(0xFF888888), fontSize = 12.sp)
                Text(exampleOutput, color = Orange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
