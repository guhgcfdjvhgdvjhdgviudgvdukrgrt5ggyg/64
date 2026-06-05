package com.ghosttype.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttype.ime.FloatingPointerService
import com.ghosttype.utils.SettingsStore

private val BlueP     = Color(0xFF2196F3)
private val BlueDark  = Color(0xFF1565C0)
private val GreenOk   = Color(0xFF4CAF50)
private val RedErr    = Color(0xFFFF5252)
private val OrangeP   = Color(0xFFFF8C00)

@Composable
fun PointerScreen() {
    val ctx   = LocalContext.current
    val prefs = remember { SettingsStore.prefs(ctx) }

    var pointerEnabled    by remember { mutableStateOf(prefs.getBoolean(SettingsStore.KEY_POINTER_ENABLED, false)) }
    var multiClick        by remember { mutableStateOf(prefs.getBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, false)) }
    var pointerX          by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_POINTER_X, -1)) }
    var pointerY          by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_POINTER_Y, -1)) }
    var dotRunning        by remember { mutableStateOf(FloatingPointerService.instance != null) }
    var dotLocked         by remember { mutableStateOf(prefs.getBoolean(SettingsStore.KEY_POINTER_LOCKED, false)) }
    var pointerSizeDp     by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_POINTER_SIZE_DP, 28).coerceIn(16, 72)) }
    var hasOverlayPerm    by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var clickDelayMs      by remember { mutableStateOf(prefs.getInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, 0).coerceIn(0, 5000)) }

    // Refresh position + dot status every 600 ms so UI always stays in sync
    LaunchedEffect(Unit) {
        while (isActive) {
            kotlinx.coroutines.delay(600)
            pointerX       = prefs.getInt(SettingsStore.KEY_POINTER_X, -1)
            pointerY       = prefs.getInt(SettingsStore.KEY_POINTER_Y, -1)
            dotRunning     = FloatingPointerService.instance != null
            dotLocked      = prefs.getBoolean(SettingsStore.KEY_POINTER_LOCKED, false)
            hasOverlayPerm = Settings.canDrawOverlays(ctx)
        }
    }

    val positionSet = pointerX >= 0 && pointerY >= 0
    val dotVisible = dotRunning && !dotLocked

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── Header ──────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(BlueP.copy(alpha = 0.30f), BlueDark.copy(alpha = 0.15f))))
                    .border(1.5.dp, Brush.verticalGradient(listOf(BlueP.copy(alpha = 0.7f), BlueDark.copy(alpha = 0.4f))), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Text("🎯", fontSize = 26.sp) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pointer", color = BlueP, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text("Auto-click send button for any app", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }

        // ── Status banner ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (pointerEnabled && positionSet) GreenOk.copy(alpha = 0.10f)
                    else if (pointerEnabled) OrangeP.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    1.dp,
                    if (pointerEnabled && positionSet) GreenOk.copy(alpha = 0.35f)
                    else if (pointerEnabled) OrangeP.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(
                    when {
                        pointerEnabled && positionSet -> GreenOk
                        pointerEnabled -> OrangeP
                        else -> Color(0xFF888888)
                    }
                )
            )
            Text(
                when {
                    pointerEnabled && positionSet -> "✅ Dot shown at X:$pointerX  Y:$pointerY — tap Start Click to begin"
                    pointerEnabled -> "⚠ Position not set — show dot and drag it to Send button"
                    else -> "Pointer is OFF"
                },
                color = when {
                    pointerEnabled && positionSet -> GreenOk
                    pointerEnabled -> OrangeP
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // ── Pointer Show + Start Click buttons ──────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    pointerEnabled = true
                    dotRunning = true
                    prefs.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, true).apply()
                    FloatingPointerService.showDotOnly(ctx)
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BlueP,
                    contentColor = Color.White
                )
            ) {
                Text("🎯  Pointer Show", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
            Button(
                onClick = {
                    pointerEnabled = true
                    prefs.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, true).apply()
                    if (!dotRunning) {
                        dotRunning = true
                        FloatingPointerService.showDotOnly(ctx)
                    }
                    FloatingPointerService.startAutoClick(ctx)
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF8C00),
                    contentColor = Color.Black
                )
            ) {
                Text("▶  Start Click", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
        }

        // ── Dot control card ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Dot Control", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            // Dot status row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (dotVisible) GreenOk.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (dotVisible) GreenOk else Color(0xFF888888)))
                Text(
                    when {
                        dotVisible -> "🟢 Orange dot is visible on screen"
                        dotRunning && dotLocked -> "🔒 Dot is locked (hidden)"
                        else -> "Dot is hidden"
                    },
                    color = if (dotVisible) GreenOk else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }

            // Show / Hide buttons
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        pointerEnabled = true
                        dotRunning = true
                        prefs.edit().putBoolean(SettingsStore.KEY_POINTER_ENABLED, true).apply()
                        FloatingPointerService.showDotOnly(ctx)
                    },
                    enabled = !dotRunning,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BlueP, contentColor = Color.White,
                        disabledContainerColor = BlueP.copy(alpha = 0.22f),
                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("🎯  Show Dot", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
                OutlinedButton(
                    onClick = {
                        FloatingPointerService.stop(ctx)
                        dotRunning = false
                    },
                    enabled = dotRunning,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RedErr),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (dotRunning) RedErr.copy(alpha = 0.6f) else RedErr.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("✕  Hide Dot", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            // Position display
            AnimatedVisibility(visible = positionSet) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GreenOk.copy(alpha = 0.08f))
                        .border(1.dp, GreenOk.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("📍", fontSize = 20.sp)
                    Column {
                        Text("Send button position saved", color = GreenOk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("X: $pointerX   Y: $pointerY", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Overlay permission warning ──────────────────────────
        if (!hasOverlayPerm) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFF8C00).copy(alpha = 0.12f))
                    .border(1.dp, Color(0xFFFF8C00).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("⚠", fontSize = 16.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Permission required", color = OrangeP, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("\"Draw over other apps\" must be ON for the pointer dot to appear", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeP, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("Allow", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }

        // ── Dot size slider ─────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Dot Size", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔵", fontSize = (pointerSizeDp * 0.28f).sp)
                    Text("${pointerSizeDp}dp", color = OrangeP, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Slider(
                value = pointerSizeDp.toFloat(),
                onValueChange = { pointerSizeDp = it.toInt() },
                onValueChangeFinished = {
                    prefs.edit().putInt(SettingsStore.KEY_POINTER_SIZE_DP, pointerSizeDp).apply()
                    if (dotRunning) FloatingPointerService.resize(ctx)
                },
                valueRange = 16f..72f,
                steps = 27,
                colors = SliderDefaults.colors(
                    thumbColor = OrangeP,
                    activeTrackColor = OrangeP,
                    inactiveTrackColor = OrangeP.copy(alpha = 0.25f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Small (16dp)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Text("Large (72dp)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }

        // ── Settings card ───────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Multi-click (3× per send)", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Text("Click 3 times for stubborn send buttons", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Switch(
                    checked = multiClick,
                    onCheckedChange = {
                        multiClick = it
                        prefs.edit().putBoolean(SettingsStore.KEY_POINTER_MULTI_CLICK, it).apply()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = OrangeP)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // ── Click delay slider ───────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Click Delay", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Wait this long after typing before clicking Send", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            clickDelayMs = (clickDelayMs - 500).coerceAtLeast(0)
                            prefs.edit().putInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, clickDelayMs).apply()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeP),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, OrangeP.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Text("−", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (clickDelayMs == 0) "Off" else "${clickDelayMs / 1000}.${(clickDelayMs % 1000) / 100}s",
                            color = OrangeP,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            clickDelayMs = (clickDelayMs + 500).coerceAtMost(5000)
                            prefs.edit().putInt(SettingsStore.KEY_POINTER_CLICK_DELAY_MS, clickDelayMs).apply()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeP),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, OrangeP.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                }
            }
        }

        // ── How to use ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BlueP.copy(alpha = 0.06f))
                .border(1.dp, BlueP.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("How to use Pointer", color = BlueP, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)

            listOf(
                "1" to "Enable Pointer toggle above",
                "2" to "Tap 'Show Dot' — a blue dot appears on screen",
                "3" to "Open your chat app (WhatsApp, Telegram, etc.)",
                "4" to "Drag the blue dot exactly onto the Send button",
                "5" to "Come back to Auto-Type screen",
                "6" to "Press START — after each message, pointer will auto-click Send!"
            ).forEach { (num, step) ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(BlueP.copy(alpha = 0.18f))
                            .border(1.dp, BlueP.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(num, color = BlueP, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                    Text(step, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }

            HorizontalDivider(color = BlueP.copy(alpha = 0.15f), thickness = 0.5.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(OrangeP.copy(alpha = 0.08f))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("💡", fontSize = 16.sp)
                Text(
                    "Use the pointer in apps where auto-send does not work (e.g. some custom apps). WhatsApp / Telegram do not need the pointer — auto-detect handles it automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}
