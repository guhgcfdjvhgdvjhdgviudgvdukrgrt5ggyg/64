package com.ghosttype.ime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.ghosttype.utils.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws a small orange floating dot overlay that the user can drag over the SEND button
 * of any chat app. The position is saved to SharedPreferences. AutoTypeEngine then uses
 * AccessibilityService.dispatchGesture() to "tap" that exact (x,y) after each typed line.
 *
 * AUTO-CLICK MODE:
 *   When the service starts (pointer is ON), a coroutine loop fires a gesture click at
 *   the saved (x,y) every [KEY_POINTER_AUTO_CLICK_INTERVAL_MS] milliseconds (default 1 s).
 *   The dot hides itself briefly before each click so the gesture lands on the app under
 *   it, then reappears. Stop the service to stop auto-clicking.
 *
 * - When LOCKED: dot is non-touchable (pointer events pass through to the app underneath)
 *   so it does not interfere with normal use.
 * - When UNLOCKED: dot is draggable; on each ACTION_UP its (x,y) is persisted.
 *
 * Requires SYSTEM_ALERT_WINDOW (android.permission.SYSTEM_ALERT_WINDOW) — must be granted
 * by user via Settings.canDrawOverlays() flow.
 * Requires GhostType Pro or GhostType Pointer accessibility service for gesture dispatch.
 */
class FloatingPointerService : Service() {

    private var wm: WindowManager? = null
    private var dot: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var dotSizePx: Int = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoClickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        showOverlay()
        if (dot == null) {
            stopSelf()
            return
        }
        instance = this
    }

    override fun onDestroy() {
        stopAutoClick()
        scope.cancel()
        hideOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START            -> startAutoClick()
            ACTION_SHOW_ONLY        -> { /* dot shown in onCreate */ }
            ACTION_LOCK             -> setLocked(true)
            ACTION_UNLOCK           -> setLocked(false)
            ACTION_REFRESH          -> applyLockedFlag()
            ACTION_RESIZE           -> applySize()
            ACTION_AUTO_CLICK_START -> startAutoClick()
            ACTION_AUTO_CLICK_STOP  -> stopAutoClick()
        }
        return START_STICKY
    }

    // ─── Auto-click loop ─────────────────────────────────────────────────────

    private fun startAutoClick() {
        autoClickJob?.cancel()
        autoClickJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val prefs = SettingsStore.prefs(this@FloatingPointerService)
                val intervalMs = prefs.getLong(
                    SettingsStore.KEY_POINTER_AUTO_CLICK_INTERVAL_MS, 1000L
                ).coerceIn(200L, 60_000L)

                delay(intervalMs)
                if (!isActive) break

                val cx = prefs.getInt(SettingsStore.KEY_POINTER_X, -1)
                val cy = prefs.getInt(SettingsStore.KEY_POINTER_Y, -1)
                if (cx < 0 || cy < 0) continue  // position not set yet

                // Hide dot so the gesture lands on the app below, not on us.
                mainHandler.post { temporarilyHideForClick(900L) }
                // Wait for the dot to be removed from WindowManager before clicking.
                delay(700L)

                // Dispatch gesture via whichever accessibility service is connected.
                // Must run on main thread — dispatchGesture() requires it.
                val clicked = withContext(Dispatchers.Main) {
                    GhostTypeAccessibilityService.instance?.clickAt(cx.toFloat(), cy.toFloat())
                        ?: GhostTypePointerService.instance?.clickAt(cx.toFloat(), cy.toFloat())
                        ?: false
                }

                // If neither service is connected, wait a bit longer before retrying.
                if (!clicked) delay(2000L)
            }
        }
    }

    private fun stopAutoClick() {
        autoClickJob?.cancel()
        autoClickJob = null
    }

    // ─── Overlay ─────────────────────────────────────────────────────────────

    private fun currentSizeDp(): Int =
        SettingsStore.prefs(this).getInt(SettingsStore.KEY_POINTER_SIZE_DP, 28).coerceIn(16, 72)

    private fun showOverlay() {
        if (dot != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizeDp = currentSizeDp()
        val sizePx = dp(sizeDp)
        dotSizePx = sizePx
        val halfPx = sizePx / 2

        val container = FrameLayout(this)
        val ring = TextView(this).apply {
            text = "●"
            textSize = sizeDp * 0.75f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF8C00"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33000000"))
                setStroke(dp(2), Color.parseColor("#FF8C00"))
            }
            alpha = 0.9f
        }
        container.addView(
            ring,
            FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = SettingsStore.prefs(this)
        val storedCx = prefs.getInt(SettingsStore.KEY_POINTER_X, -1)
        val storedCy = prefs.getInt(SettingsStore.KEY_POINTER_Y, -1)
        val locked = prefs.getBoolean(SettingsStore.KEY_POINTER_LOCKED, false)

        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            type,
            currentFlags(locked),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (storedCx >= 0) (storedCx - halfPx).coerceAtLeast(0) else 200
            y = if (storedCy >= 0) (storedCy - halfPx).coerceAtLeast(0) else 800
        }

        var downX = 0
        var downY = 0
        var touchX = 0f
        var touchY = 0f
        container.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            val half = dotSizePx / 2
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = p.x; downY = p.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (downX + (ev.rawX - touchX)).toInt()
                    p.y = (downY + (ev.rawY - touchY)).toInt()
                    runCatching { wm?.updateViewLayout(container, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    SettingsStore.prefs(this).edit()
                        .putInt(SettingsStore.KEY_POINTER_X, (p.x + half).coerceAtLeast(0))
                        .putInt(SettingsStore.KEY_POINTER_Y, (p.y + half).coerceAtLeast(0))
                        .apply()
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(container, params)
            dot = container
        } catch (_: Throwable) {}
    }

    private fun currentFlags(locked: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (locked) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun setLocked(locked: Boolean) {
        SettingsStore.prefs(this).edit().putBoolean(SettingsStore.KEY_POINTER_LOCKED, locked).apply()
        applyLockedFlag()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var hidingForClick = false

    /**
     * Fully detach the dot from the window stack for [durationMs] then re-attach.
     * Called by both AutoTypeEngine and the auto-click loop before dispatching a gesture.
     * Removal (not just FLAG_NOT_TOUCHABLE) is required because on Android 9+ the
     * system can still route accessibility gestures to overlay windows.
     */
    fun temporarilyHideForClick(durationMs: Long = 600L) {
        if (dot == null || params == null) return
        hidingForClick = true
        mainHandler.removeCallbacksAndMessages(HIDE_TOKEN)

        mainHandler.post {
            val v = dot ?: return@post
            val p = params ?: return@post
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        mainHandler.postAtTime({
            try {
                val v = dot ?: return@postAtTime
                val p = params ?: return@postAtTime
                wm?.addView(v, p)
                applyLockedFlag()
            } catch (_: Throwable) {}
            hidingForClick = false
        }, HIDE_TOKEN, android.os.SystemClock.uptimeMillis() + durationMs)
    }

    private fun applyLockedFlag() {
        val v = dot ?: return
        val p = params ?: return
        val locked = SettingsStore.prefs(this).getBoolean(SettingsStore.KEY_POINTER_LOCKED, false)
        p.flags = currentFlags(locked)
        if (locked) {
            v.visibility = View.INVISIBLE
            v.alpha = 0f
        } else {
            v.visibility = View.VISIBLE
            v.alpha = 0.9f
        }
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun applySize() {
        val v = dot ?: return
        val p = params ?: return
        val sizeDp = currentSizeDp()
        val sizePx = dp(sizeDp)
        dotSizePx = sizePx
        p.width  = sizePx
        p.height = sizePx
        runCatching { wm?.updateViewLayout(v, p) }
        v.layoutParams = v.layoutParams?.also {
            it.width  = sizePx
            it.height = sizePx
        }
        (v as? FrameLayout)?.getChildAt(0)?.let { child ->
            (child as? TextView)?.apply {
                textSize = sizeDp * 0.75f
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
            }
        }
        v.requestLayout()
    }

    private fun hideOverlay() {
        val v = dot ?: return
        runCatching { wm?.removeView(v) }
        dot = null
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private val HIDE_TOKEN = Any()

        const val ACTION_START            = "com.ghosttype.pointer.START"
        const val ACTION_SHOW_ONLY        = "com.ghosttype.pointer.SHOW_ONLY"
        const val ACTION_LOCK             = "com.ghosttype.pointer.LOCK"
        const val ACTION_UNLOCK           = "com.ghosttype.pointer.UNLOCK"
        const val ACTION_REFRESH          = "com.ghosttype.pointer.REFRESH"
        const val ACTION_RESIZE           = "com.ghosttype.pointer.RESIZE"
        const val ACTION_AUTO_CLICK_START = "com.ghosttype.pointer.AUTO_CLICK_START"
        const val ACTION_AUTO_CLICK_STOP  = "com.ghosttype.pointer.AUTO_CLICK_STOP"

        @Volatile var instance: FloatingPointerService? = null

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_START))
        }
        fun showDotOnly(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_SHOW_ONLY))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingPointerService::class.java))
        }
        fun lock(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_LOCK)
            ctx.startService(i)
        }
        fun unlock(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_UNLOCK)
            ctx.startService(i)
        }
        fun resize(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_RESIZE)
            ctx.startService(i)
        }
        fun startAutoClick(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_AUTO_CLICK_START)
            ctx.startService(i)
        }
        fun stopAutoClick(ctx: Context) {
            val i = Intent(ctx, FloatingPointerService::class.java).setAction(ACTION_AUTO_CLICK_STOP)
            ctx.startService(i)
        }

        fun isRunning(): Boolean = instance != null
    }
}
