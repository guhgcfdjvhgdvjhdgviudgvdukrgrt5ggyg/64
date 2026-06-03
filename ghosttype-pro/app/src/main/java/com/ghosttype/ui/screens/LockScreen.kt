package com.ghosttype.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghosttype.security.ApprovalGate
import com.ghosttype.security.DeviceId
import com.ghosttype.utils.SettingsStore
import kotlinx.coroutines.launch

private val Orange     = Color(0xFFFF8C00)
private val BgDark     = Color(0xFF0C0C0C)
private val CardBg     = Color(0xFF161616)
private val Divider    = Color(0xFF242424)
private val TextMuted  = Color(0xFF888888)
private val GreenWa    = Color(0xFF25D366)

private data class LockPlan(
    val icon: String, val iconDark: Color, val tag: String?, val name: String,
    val duration: String, val price: String, val accent: Color, val featured: Boolean = false
)

private val LOCK_PLANS = listOf(
    LockPlan("🎯", Color(0xFF0066AA), null,      "Trial",     "7 Days",   "FREE",    Color(0xFF66BBFF)),
    LockPlan("📅", Color(0xFFBB5500), null,      "Monthly",   "1 Month",  "Rs 50",   Orange),
    LockPlan("🔥", Color(0xFF993300), "POPULAR", "Quarterly", "3 Months", "Rs 120",  Orange, true),
    LockPlan("⚡", Color(0xFF994400), null,      "Half Year", "6 Months", "Rs 250",  Orange),
    LockPlan("👑", Color(0xFF997700), "BEST",    "Lifetime",  "Forever",  "Rs 500",  Color(0xFFFFD700), true),
)

@Composable
fun LockScreen(state: ApprovalGate.State, onRecheck: suspend () -> Unit, planExpired: Boolean = false) {
    val ctx    = LocalContext.current
    val prefs  = remember { SettingsStore.prefs(ctx) }
    val id     = remember(state, planExpired) { DeviceId.get(ctx) }
    val scope  = rememberCoroutineScope()
    var busy   by remember { mutableStateOf(false) }
    var userName by remember { mutableStateOf(prefs.getString(SettingsStore.KEY_PLANS_USER_NAME, "") ?: "") }
    var showPlanDialog by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf<LockPlan?>(null) }
    var dialogName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }

    // ── Global kill-switch: show minimal maintenance screen ────
    if (state == ApprovalGate.State.GloballyDisabled) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text("🔒", fontSize = 64.sp)
                Text(
                    "GhostType Pro",
                    color = Orange,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBg)
                        .border(1.dp, Color(0xFFFF4444).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "App Temporarily Disabled",
                        color = Color(0xFFFF4444),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "GhostType Pro has been temporarily turned off by the developer. Please wait for re-activation and check back soon.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
                OutlinedButton(
                    onClick = { scope.launch { busy = true; onRecheck(); busy = false } },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.5f))
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Orange, strokeWidth = 2.dp)
                    else Text("Check Again", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        return
    }

    val statusColor = when {
        planExpired                          -> Color(0xFFFF4444)
        state is ApprovalGate.State.Blocked  -> Color(0xFFFF4444)
        state is ApprovalGate.State.OfflineUnknown -> Color(0xFF66BBFF)
        else                                 -> Orange
    }
    val statusText = when {
        planExpired                          -> "Plan Expired"
        state is ApprovalGate.State.Blocked  -> "Access Revoked"
        state is ApprovalGate.State.OfflineUnknown -> "No Internet"
        state is ApprovalGate.State.NotApproved   -> "Awaiting Approval"
        else                                 -> "Locked"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // ── App name + status ────────────────────────────────────
        Text("GhostType Pro", color = Orange, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
        Text(statusText, color = statusColor, fontWeight = FontWeight.Medium, fontSize = 15.sp)

        // ── Plan expired banner ──────────────────────────────────
        if (planExpired) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFF4444).copy(alpha = 0.12f))
                    .border(1.5.dp, Color(0xFFFF4444).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("⏰", fontSize = 24.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Your plan has expired", color = Color(0xFFFF4444), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text("Select a new plan below to continue using GhostType Pro", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Plans as cards ───────────────────────────────────────
        Text("CHOOSE A PLAN", color = TextMuted, fontSize = 11.sp, letterSpacing = 0.8.sp)

        LOCK_PLANS.forEach { plan ->
            val accentColor = plan.accent
            val isFeatured  = plan.featured
            val bgColor     = if (isFeatured) Color(0xFF1A1000) else CardBg
            val borderColor = if (isFeatured) accentColor.copy(alpha = 0.45f) else Divider

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .border(if (isFeatured) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
                    .clickable {
                        selectedPlan = plan
                        dialogName = userName
                        nameError = false
                        showPlanDialog = true
                    }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(accentColor, plan.iconDark)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            plan.icon,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (plan.icon.length == 1) 22.sp else 18.sp
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(plan.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            if (plan.tag != null) {
                                Text(plan.tag, color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(accentColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text(plan.duration, color = TextMuted, fontSize = 12.sp)
                    }
                    Text(plan.price, color = accentColor, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }

                // Name + Key preview rows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D0D0D))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Name row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("👤", fontSize = 13.sp)
                        Text(
                            if (userName.isNotBlank()) userName else "—",
                            color = if (userName.isNotBlank()) Color.White else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    // Key row — full width, ellipsis at end
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("🔑", fontSize = 13.sp)
                        Text(
                            id,
                            color = Color(0xFFFFCC66),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("GhostType Device ID", id))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("📋", fontSize = 12.sp)
                        }
                    }
                }

                // Tap hint
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.10f))
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📲 Tap to Subscribe via WhatsApp", color = accentColor.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Device ID (after plans) ───────────────────────────────
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device ID", color = TextMuted, fontSize = 12.sp, letterSpacing = 0.8.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .padding(start = 12.dp, end = 4.dp)
                ) {
                    Text(
                        id,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("GhostType Device ID", id))
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Text("📋", fontSize = 18.sp)
                    }
                }
            }
        }

        // ── Re-check ─────────────────────────────────────────────
        Button(
            onClick = { if (!busy) { busy = true; scope.launch { try { onRecheck() } finally { busy = false } } } },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Checking...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Text("Re-check Approval", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        // ── Contact ──────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Contact", color = TextMuted, fontSize = 12.sp, letterSpacing = 0.8.sp)
                listOf(
                    Triple("📢  WhatsApp Channel",   GreenWa,             "https://whatsapp.com/channel/0029Va9UKCmAzNZFCsqkMf1O"),
                    Triple("💬  WhatsApp Community", GreenWa,             "https://chat.whatsapp.com/Eu1VZJfFpaz7gJxoGr4PvR"),
                    Triple("📸  @chand.tricker",     Color(0xFFE1306C),   "https://www.instagram.com/chand.tricker?igsh=c2dhbHFyZXdrZmpp"),
                ).forEach { (label, tint, url) ->
                    TextButton(
                        onClick = {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = tint),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                    }
                    HorizontalDivider(color = Divider, thickness = 0.5.dp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ── Plan subscription dialog ───────────────────────────
    if (showPlanDialog && selectedPlan != null) {
        val plan = selectedPlan!!
        Dialog(
            onDismissRequest = { showPlanDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(plan.icon, fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Subscribe to ${plan.name}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("${plan.duration} — ${plan.price}", color = plan.accent, fontSize = 14.sp)
                }

                Text("YOUR NAME", color = TextMuted, fontSize = 11.sp, letterSpacing = 0.8.sp)
                OutlinedTextField(
                    value = dialogName,
                    onValueChange = { dialogName = it; nameError = false },
                    placeholder = { Text("Enter your name", color = TextMuted) },
                    singleLine = true,
                    isError = nameError,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = if (nameError) Color(0xFFFF4444) else Divider,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Orange,
                        errorBorderColor = Color(0xFFFF4444),
                        errorLabelColor = Color(0xFFFF4444)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError) {
                    Text("Name is required", color = Color(0xFFFF4444), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D0D0D))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔑 $id", color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 1)
                }

                Button(
                    onClick = {
                        val name = dialogName.trim()
                        if (name.isEmpty()) {
                            nameError = true
                            return@Button
                        }
                        nameError = false
                        prefs.edit().putString(SettingsStore.KEY_PLANS_USER_NAME, name).apply()
                        userName = name

                        val now = System.currentTimeMillis()
                        val durMs = when (plan.name) {
                            "Trial"     -> 7L  * 24 * 3600 * 1000
                            "Monthly"   -> 30L * 24 * 3600 * 1000
                            "Quarterly" -> 90L * 24 * 3600 * 1000
                            "Half Year" -> 180L * 24 * 3600 * 1000
                            "Lifetime"  -> -1L
                            else        -> 30L * 24 * 3600 * 1000
                        }
                        prefs.edit()
                            .putString(SettingsStore.KEY_ACTIVE_PLAN_NAME, plan.name)
                            .putString(SettingsStore.KEY_ACTIVE_PLAN_PRICE, plan.price)
                            .putString(SettingsStore.KEY_ACTIVE_PLAN_DURATION, plan.duration)
                            .apply()
                        if (durMs < 0) {
                            prefs.edit()
                                .putLong(SettingsStore.KEY_PLAN_STARTED_MS, now)
                                .putLong(SettingsStore.KEY_PLAN_EXPIRY_MS, 0L)
                                .apply()
                        } else {
                            prefs.edit()
                                .putLong(SettingsStore.KEY_PLAN_STARTED_MS, now)
                                .putLong(SettingsStore.KEY_PLAN_EXPIRY_MS, now + durMs)
                                .apply()
                        }
                        val msg = buildString {
                            append("*GhostType Pro — Plan Request*\n\n")
                            append("👤 *Name:* $name\n")
                            append("🔑 *Key:* $id\n")
                            append("📦 *Plan:* ${plan.name} (${plan.duration}) — ${plan.price}")
                        }
                        val encoded = Uri.encode(msg)
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://wa.me/923017787729?text=$encoded"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                        showPlanDialog = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Send via WhatsApp", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                TextButton(
                    onClick = { showPlanDialog = false },
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("Cancel", color = TextMuted, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
