package com.ghosttype.ime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.ghosttype.utils.SettingsStore

/**
 * GhostType Pointer — dedicated accessibility service for gesture / tap dispatch.
 *
 * Responsibilities:
 *  1. Listen for ACTION_POINTER_CLICK broadcast → tap at saved pointer coordinates.
 *  2. Expose [clickAt] so AutoTypeEngine can use it directly when this service is
 *     connected, without needing the heavier GhostTypeAccessibilityService.
 *
 * In Android Accessibility Settings this shows up as a SEPARATE entry
 * ("GhostType Pointer") distinct from "GhostType Pro", so the user can
 * grant only gesture-dispatch permission without window-content reading.
 */
class GhostTypePointerService : AccessibilityService() {

    private val clickReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_POINTER_CLICK) {
                val prefs = SettingsStore.prefs(this@GhostTypePointerService)
                val x = prefs.getFloat(SettingsStore.KEY_POINTER_X, -1f)
                val y = prefs.getFloat(SettingsStore.KEY_POINTER_Y, -1f)
                if (x >= 0 && y >= 0) clickAt(x, y)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter(ACTION_POINTER_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(clickReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(clickReceiver) } catch (_: Throwable) {}
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Dispatch a tap gesture at [x],[y]. The 1-px lineTo ensures Android
     * treats it as a real DOWN→MOVE→UP sequence (zero-length paths are
     * silently dropped). Duration 120 ms matches the main service.
     */
    fun clickAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile var instance: GhostTypePointerService? = null

        /** Broadcast action that triggers a pointer click at saved coords. */
        const val ACTION_POINTER_CLICK = "com.ghosttype.action.POINTER_CLICK"

        /** Send this broadcast to trigger a tap at the saved pointer position. */
        fun triggerClick(ctx: Context) {
            ctx.sendBroadcast(Intent(ACTION_POINTER_CLICK).setPackage(ctx.packageName))
        }
    }
}
