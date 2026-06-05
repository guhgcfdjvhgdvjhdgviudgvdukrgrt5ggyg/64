package com.ghosttype.ui.screens

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

// Use InputMethodManager API — reliable across all Android versions
private fun safeImeEnabled(ctx: Context): Boolean = try {
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.enabledInputMethodList.any { it.packageName.contains("com.ghosttype") }
} catch (_: Throwable) {
    // Fallback to Settings
    try {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: ""
        list.contains("com.ghosttype")
    } catch (_: Throwable) { false }
}

private fun safeImeDefault(ctx: Context): Boolean = try {
    val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
    cur.contains("com.ghosttype")
} catch (_: Throwable) { false }

private fun safeAccessibilityOn(ctx: Context): Boolean = try {
    val flag = try { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) } catch (_: Throwable) { 0 }
    val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    flag == 1 && list.contains("com.ghosttype")
} catch (_: Throwable) { false }

@Composable
fun HomeScreen(onOpenImeSettings: () -> Unit, onOpenAccessibility: () -> Unit) {
    val ctx = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    // Refresh on resume (when returning from settings)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Also poll every 1.5s so it updates the moment user toggles
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1500)
            refreshTick++
        }
    }

    val imeEnabled      = remember(refreshTick) { safeImeEnabled(ctx) }
    val imeSelected     = remember(refreshTick) { safeImeDefault(ctx) }
    val accessibilityOn = remember(refreshTick) { safeAccessibilityOn(ctx) }
    val allReady       = imeEnabled && imeSelected && accessibilityOn

    // ── Interstitial ad ────────────────────────────────────────
    var lastAdMs by remember { mutableLongStateOf(0L) }
    val interHandle = rememberInterstitialAd()
    LaunchedEffect(Unit) {
        delay(2000)
        val elapsed = System.currentTimeMillis() - lastAdMs
        if (elapsed > 60_000L && interHandle.isLoaded()) {
            interHandle.show()
            lastAdMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Product header ───────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("GhostType Pro", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "A professional Android keyboard with Auto-Type engine, multi-language support, clipboard manager and font system.",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
        }

        // ── Setup status ─────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (allReady)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else
                    MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (allReady) "✓  Setup complete" else "Setup required",
                    color = if (allReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (allReady)
                        "GhostType Pro is active and ready to use."
                    else
                        "Complete the steps below to activate the keyboard.",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
                if (!allReady) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { refreshTick++ },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Re-check status") }
                }
            }
        }

        // ── Setup steps ──────────────────────────────────────
        StepCard("1", "Enable the keyboard",
            "Go to System Settings -> Languages & Input -> Manage Keyboards and toggle GhostType Pro ON.",
            "Open Keyboard Settings", imeEnabled, onOpenImeSettings)

        StepCard("2", "Set as default input method",
            "Tap any text field and select GhostType Pro from the input method picker, or long-press the spacebar.",
            "Open Input Picker", imeSelected) {
            try {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showInputMethodPicker()
            } catch (_: Throwable) {}
        }

        StepCard("3", "Grant Accessibility permission",
            "Required for Auto-Type to detect and tap the Send button in WhatsApp, Messenger and Telegram.",
            "Open Accessibility Settings", accessibilityOn, onOpenAccessibility)

    }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    body: String,
    action: String,
    done: Boolean,
    onClick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Step $number  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), fontSize = 14.sp)
                Text(
                    if (done) "✓ Done" else "Pending",
                    color = if (done) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                )
            ) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}
