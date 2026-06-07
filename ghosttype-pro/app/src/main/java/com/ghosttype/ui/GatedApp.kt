package com.ghosttype.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ghosttype.BuildConfig
import com.ghosttype.security.ApprovalGate
import com.ghosttype.security.DeviceId
import com.ghosttype.security.SecurityGuard
import com.ghosttype.ui.screens.LockScreen
import com.ghosttype.utils.SettingsStore
import kotlinx.coroutines.delay

/**
 * Wraps the entire main UI tree behind the security + approval gate.
 *
 *   1. SecurityGuard.verifyOrDie — signature pinning, anti-debug,
 *      production-build flag check. Failure → permanent lock screen.
 *   2. ApprovalGate.evaluate — fetches users.json from GitHub.
 *   3. Version enforcement — if remote app_version ≠ installed version,
 *      the app is FORCE-BLOCKED for ALL users with no dismiss option.
 *      Only the exact matching version is allowed to run.
 */
@Composable
fun GatedApp(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<ApprovalGate.State?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        if (!SecurityGuard.verifyOrDie(ctx)) {
            state = ApprovalGate.State.Blocked
            return@LaunchedEffect
        }
        state = ApprovalGate.evaluate(ctx, force = true)
    }

    // Listen for background revocation broadcast
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                if (i?.action == com.ghosttype.security.ApprovalRefreshWorker.ACTION_APPROVAL_REVOKED) {
                    refreshTick++
                }
            }
        }
        val filter = android.content.IntentFilter(
            com.ghosttype.security.ApprovalRefreshWorker.ACTION_APPROVAL_REVOKED
        )
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {}
        onDispose { try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {} }
    }

    // Plan expiry ticker
    var planExpired by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        while (state is ApprovalGate.State.Approved) {
            if (SettingsStore.isPlanExpired(ctx)) {
                DeviceId.reset(ctx)
                planExpired = true
                break
            }
            delay(10_000L)
        }
    }

    // ── Update-gate globals ───────────────────────────────────────
    val prefs = remember { SettingsStore.prefs(ctx) }
    var prefsTick by remember { mutableStateOf(0) }

    // Listen for SharedPreferences changes from watchdog / workers and
    // trigger recomposition so the force-update / disable screen appears
    // immediately — no app restart or approval re-fetch needed.
    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val watchKeys = setOf(
                "update_gate_disabled",
                SettingsStore.KEY_REMOTE_APP_VERSION,
                SettingsStore.KEY_DOWNLOAD_URL,
                "crash_app_triggered"
            )
            if (key in watchKeys) prefsTick++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val globallyDisabled by remember(prefsTick) {
        derivedStateOf { prefs.getBoolean("update_gate_disabled", false) }
    }

    // ── Version enforcement ────────────────────────────────────────
    // Reads prefs via prefsTick so any write from watchdog / background
    // worker is reflected instantly in the UI.
    val remoteVersion by remember(prefsTick) {
        derivedStateOf { prefs.getString(SettingsStore.KEY_REMOTE_APP_VERSION, "") ?: "" }
    }
    val downloadUrl by remember(prefsTick) {
        derivedStateOf { prefs.getString(SettingsStore.KEY_DOWNLOAD_URL, "") ?: "" }
    }
    // Version mismatch: FORCE-BLOCK — no dismiss, no "Later".
    // Applies to every user (approved or not) once the JSON is fetched.
    val versionMismatch = state != null &&
            remoteVersion.isNotBlank() &&
            remoteVersion != BuildConfig.VERSION_NAME

    // ── Crash detection from watchdog ──────────────────────────────
    LaunchedEffect(prefsTick) {
        if (prefs.getBoolean("crash_app_triggered", false)) {
            val i = android.content.Intent(ctx, BrickedActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(i)
        }
    }

    // ── Render tree ───────────────────────────────────────────────
    when {
        // 1. Loading
        state == null -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0C0C0C)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("👻", fontSize = 48.sp)
                    CircularProgressIndicator(color = Color(0xFFFF8C00), strokeWidth = 3.dp)
                    Text(
                        "GhostType Pro",
                        color = Color(0xFFFF8C00),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                }
            }
        }

        // 2. Global disable from UpdateGate (app_enabled == false)
        globallyDisabled -> {
            LockScreen(
                state = ApprovalGate.State.GloballyDisabled,
                onRecheck = { state = ApprovalGate.evaluate(ctx, force = true) }
            )
        }

        // 3. Version mismatch — force-block EVERYONE (even approved users).
        //    No "Later" button — old version is completely unusable.
        versionMismatch -> {
            ForceUpdateScreen(
                currentVersion = BuildConfig.VERSION_NAME,
                remoteVersion  = remoteVersion,
                downloadUrl    = downloadUrl
            )
        }

        // 3. Normal gate result
        state is ApprovalGate.State.Approved -> {
            if (planExpired) {
                LockScreen(
                    state     = state!!,
                    onRecheck = { state = ApprovalGate.evaluate(ctx, force = true) },
                    planExpired = true
                )
            } else {
                content()
            }
        }

        else -> LockScreen(
            state     = state!!,
            onRecheck = { state = ApprovalGate.evaluate(ctx, force = true) }
        )
    }
}

// ── Force-update full-screen block ────────────────────────────────

@Composable
private fun ForceUpdateScreen(
    currentVersion: String,
    remoteVersion:  String,
    downloadUrl:    String
) {
    val ctx    = LocalContext.current
    val Orange = Color(0xFFFF8C00)
    val Red    = Color(0xFFFF3B30)
    val BgDark = Color(0xFF0C0C0C)
    val CardBg = Color(0xFF161616)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Orange.copy(alpha = 0.12f))
                    .border(2.dp, Orange.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("⬆️", fontSize = 40.sp)
            }

            // Title
            Text(
                "Update Required",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            // Info card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBg)
                    .border(1.5.dp, Red.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "This version of GhostType Pro is no longer supported.",
                    color = Red,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)

                // Version row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Installed", color = Color(0xFF888888), fontSize = 11.sp)
                        Text(
                            "v$currentVersion",
                            color = Red,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }
                    Text("->", color = Color(0xFF555555), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Required", color = Color(0xFF888888), fontSize = 11.sp)
                        Text(
                            "v$remoteVersion",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)

                Text(
                    "Please download the latest version to continue using GhostType Pro.",
                    color = Color(0xFF888888),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Download button — only actionable option, no dismiss
            Button(
                onClick = {
                    val url = downloadUrl.ifBlank { "https://github.com/chandtricker-2/keyboardautotyper-approvals/releases" }
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {}
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) {
                Text(
                    "⬇  Download v$remoteVersion",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = Color.Black
                )
            }

            Text(
                "You must update to continue.\nNo other option is available.",
                color = Color(0xFF555555),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}
