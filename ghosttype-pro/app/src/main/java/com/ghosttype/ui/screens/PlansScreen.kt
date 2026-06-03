package com.ghosttype.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.ghosttype.security.DeviceId
import com.ghosttype.utils.SettingsStore
import kotlinx.coroutines.delay

private val Orange  = Color(0xFFFF8C00)
private val Gold    = Color(0xFFFFD700)
private val SkyBlue = Color(0xFF66BBFF)

private data class Plan(
    val emoji: String,
    val tag: String?,
    val name: String,
    val duration: String,
    val price: String,
    val accent: Color,
    val perks: List<String>,
    val featured: Boolean = false
)

private val PLANS = listOf(
    Plan("🧪", "TEST",   "Test",      "10 Min",   "FREE",    SkyBlue,
        listOf("10-minute trial run", "All features enabled", "Auto-expires in 10 min")),
    Plan("🎯", null,     "Trial",     "7 Days",   "FREE",    SkyBlue,
        listOf("Full keyboard access", "All themes", "Auto-Type basic")),
    Plan("📅", null,     "Monthly",   "1 Month",  "Rs 50",   Orange,
        listOf("Everything in Trial", "Priority support", "All features")),
    Plan("🔥", "POPULAR","Quarterly", "3 Months", "Rs 120",  Orange,
        listOf("Everything in Monthly", "Save Rs 30", "Best value"), featured = true),
    Plan("⚡", null,     "Half Year", "6 Months", "Rs 250",  Orange,
        listOf("Everything in Quarterly", "Save Rs 50", "Early access")),
    Plan("👑", "BEST",   "Lifetime",  "Forever",  "Rs 500",  Gold,
        listOf("Everything, forever", "One-time payment", "All updates free"), featured = true),
)

private fun formatCountdown(remainingMs: Long): String {
    if (remainingMs <= 0) return "Expired"
    val days  = remainingMs / (24 * 3600 * 1000)
    val hours = (remainingMs % (24 * 3600 * 1000)) / (3600 * 1000)
    val mins  = (remainingMs % (3600 * 1000)) / 60000
    val secs  = (remainingMs % 60000) / 1000
    return when {
        days > 0  -> "${days}d ${hours}h ${mins}m"
        hours > 0 -> "${hours}h ${mins}m ${secs}s"
        else      -> "${mins}m ${secs}s"
    }
}

@Composable
fun PlansScreen() {
    val ctx      = LocalContext.current
    val prefs    = remember { SettingsStore.prefs(ctx) }
    val deviceId = remember { DeviceId.get(ctx) }

    val githubPlan = remember {
        prefs.getString(SettingsStore.KEY_GITHUB_APPROVED_PLAN, "") ?: ""
    }
    val githubLockedPlan = remember(githubPlan) {
        PLANS.find { it.name.equals(githubPlan, ignoreCase = true) }
    }

    var selectedPlan by remember {
        mutableStateOf(
            if (githubPlan.isNotBlank()) githubLockedPlan
            else prefs.getString(SettingsStore.KEY_ACTIVE_PLAN_NAME, "")?.let { saved ->
                PLANS.find { it.name == saved }
            }
        )
    }
    val userName = remember { prefs.getString(SettingsStore.KEY_PLANS_USER_NAME, "") ?: "" }

    val planStartedMs = remember { prefs.getLong(SettingsStore.KEY_PLAN_STARTED_MS, 0L) }

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val expiryMs = remember(selectedPlan) {
        prefs.getLong(SettingsStore.KEY_PLAN_EXPIRY_MS, 0L)
    }
    val isLifetime  = selectedPlan?.name == "Lifetime"
    val remainingMs = if (isLifetime || expiryMs <= 0L) Long.MAX_VALUE else expiryMs - nowMs
    val planExpired = !isLifetime && expiryMs > 0L && remainingMs <= 0L

    LaunchedEffect(selectedPlan, expiryMs) {
        while (isActive) {
            delay(1000L)
            nowMs = System.currentTimeMillis()
            if (!isLifetime && expiryMs > 0L && System.currentTimeMillis() >= expiryMs) break
        }
    }

    val Green = Color(0xFF4CAF50)
    val Red   = Color(0xFFFF3B30)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ── Page title ─────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Plans", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            Text("Choose a plan and send your request to CHAND for activation.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }

        // ── Device Key card ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("🔑", fontSize = 16.sp)
                Text("Your Device Key", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            }
            Text(
                deviceId,
                color = Color(0xFFFFCC66),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text("Send this key to CHAND when requesting plan activation.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }

        // ══════════════════════════════════════════════════════
        // SECTION 1 — CURRENT PLANS
        // ══════════════════════════════════════════════════════
        SectionHeader(title = "Current Plans", icon = "📋")

        if (selectedPlan != null) {
            val bannerColor = when {
                planExpired  -> Red
                isLifetime   -> Gold
                remainingMs < 24 * 3600 * 1000L       -> Red
                remainingMs < 3 * 24 * 3600 * 1000L   -> Orange
                else         -> Green
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(bannerColor.copy(alpha = 0.10f))
                    .border(1.5.dp, bannerColor.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Plan name + countdown row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bannerColor.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(selectedPlan!!.emoji, fontSize = 24.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(selectedPlan!!.name, color = bannerColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text(selectedPlan!!.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        when {
                            planExpired -> {
                                Text("EXPIRED", color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                Text("Select a new plan below", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            isLifetime -> {
                                Text("♾ Lifetime", color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                Text("Never expires", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            expiryMs <= 0L -> {
                                Text("Pending", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Awaiting activation", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            else -> {
                                Text(formatCountdown(remainingMs), color = bannerColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                Text("remaining", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }
                    }
                }

                // Progress bar (only for timed plans with started timestamp)
                if (!planExpired && !isLifetime && expiryMs > 0L) {
                    val startMs = prefs.getLong(SettingsStore.KEY_PLAN_STARTED_MS, 0L)
                    if (startMs > 0L) {
                        val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                        LinearProgressIndicator(
                            progress = {
                                val total   = (expiryMs - startMs).toFloat()
                                val elapsed = (nowMs - startMs).toFloat()
                                (1f - (elapsed / total).coerceIn(0f, 1f))
                            },
                            color = bannerColor,
                            trackColor = bannerColor.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            "Expires: ${fmt.format(java.util.Date(expiryMs))}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }

                // Perks
                HorizontalDivider(color = bannerColor.copy(alpha = 0.25f), thickness = 0.5.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectedPlan!!.perks.forEach { perk ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("✓", color = bannerColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            Text(perk, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        }
                    }
                }

                // Price row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Price", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(selectedPlan!!.price, color = bannerColor, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }

                // GitHub-locked badge
                if (githubPlan.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Green.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("✅", fontSize = 14.sp)
                        Text("Approved & locked by CHAND", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (userName.isNotBlank()) {
                    Text("👤 $userName", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        } else {
            // No plan selected yet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("📭", fontSize = 32.sp)
                    Text("No active plan", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Select a plan from the list below", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }

        // ══════════════════════════════════════════════════════
        // SECTION 2 — LISTS PLANS (read-only info list)
        // ══════════════════════════════════════════════════════
        SectionHeader(title = "Lists Plans", icon = "📦")

        PLANS.forEach { plan ->
            PlanInfoCard(plan = plan)
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Section header ─────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(icon, fontSize = 18.sp)
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp
        )
    }
}

// ── Plan card ──────────────────────────────────────────────────

@Composable
private fun PlanCard(plan: Plan, isSelected: Boolean, onClick: () -> Unit) {
    val Green = Color(0xFF4CAF50)
    val borderColor = when {
        isSelected    -> Green.copy(alpha = 0.7f)
        plan.featured -> plan.accent.copy(alpha = 0.5f)
        else          -> MaterialTheme.colorScheme.outlineVariant
    }
    val bgBrush = when {
        isSelected    -> Brush.verticalGradient(listOf(Green.copy(alpha = 0.12f), Green.copy(alpha = 0.04f)))
        plan.featured -> Brush.verticalGradient(listOf(plan.accent.copy(alpha = 0.10f), plan.accent.copy(alpha = 0.03f)))
        else          -> Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bgBrush)
            .border(if (isSelected || plan.featured) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Green.copy(alpha = 0.18f) else plan.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Text(if (isSelected) "✅" else plan.emoji, fontSize = 26.sp) }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    if (isSelected) {
                        Text("SELECTED", color = Green, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Green.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
                    } else if (plan.tag != null) {
                        Text(plan.tag, color = if (plan.accent == Gold) Color(0xFF7A5F00) else plan.accent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(plan.accent.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text(plan.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            Text(plan.price, color = if (isSelected) Green else plan.accent, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }

        HorizontalDivider(color = borderColor.copy(alpha = 0.4f), thickness = 0.5.dp)

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            plan.perks.forEach { perk ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓", color = if (isSelected) Green else plan.accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Text(perk, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }
            }
        }

        if (!isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(plan.accent.copy(alpha = 0.08f))
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Tap to select this plan", color = plan.accent.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Plan info card (read-only, no selection) ───────────────────

@Composable
private fun PlanInfoCard(plan: Plan) {
    val borderColor = when {
        plan.featured -> plan.accent.copy(alpha = 0.5f)
        else          -> MaterialTheme.colorScheme.outlineVariant
    }
    val bgBrush = when {
        plan.featured -> Brush.verticalGradient(listOf(plan.accent.copy(alpha = 0.10f), plan.accent.copy(alpha = 0.03f)))
        else          -> Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bgBrush)
            .border(if (plan.featured) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(plan.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Text(plan.emoji, fontSize = 26.sp) }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    if (plan.tag != null) {
                        Text(
                            plan.tag,
                            color = if (plan.accent == Gold) Color(0xFF7A5F00) else plan.accent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(plan.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(plan.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            Text(plan.price, color = plan.accent, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }

        HorizontalDivider(color = borderColor.copy(alpha = 0.4f), thickness = 0.5.dp)

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            plan.perks.forEach { perk ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓", color = plan.accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Text(perk, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }
            }
        }
    }
}
