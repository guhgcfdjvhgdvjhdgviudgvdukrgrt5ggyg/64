package com.ghosttype.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import com.ghosttype.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.ZipFile

/**
 * Hardened environment & tamper checks that run at multiple points
 * (Application, every Activity, every Service, background watchdog).
 *
 * If ANY check fails the app is considered compromised and must show
 * the BrickedActivity.
 */
object Hardener {

    /** All checks must pass. */
    fun isEnvironmentSafe(ctx: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        if (!ObfConstants.IS_OBFUSCATED) return true

        // Native library must be loadable — tampered APKs often strip it.
        if (!NativeGuard.ensureLoaded()) return false
        // Native signing SHA verification (harder to bypass than Kotlin).
        if (!NativeGuard.verify(ctx)) return false
        // Native debugger check.
        if (NativeGuard.isDebuggerAttached()) return false

        return !isRooted()               &&
               !isDebuggerAttached()     &&
               !isEmulator()             &&
               !isFridaPresent()         &&
               !isCodeTampered(ctx)      &&
               !isApkModified(ctx)
    }

    // ─── Root detection ──────────────────────────────────────────

    private fun isRooted(): Boolean {
        val suPaths = arrayOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/system/priv-app/Superuser.apk",
            "/magisk", "/data/adb/magisk", "/data/adb/magisk.db",
            "/system/xbin/busybox"
        )
        for (p in suPaths) {
            if (File(p).exists()) return true
        }
        // which su
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val rdr = BufferedReader(InputStreamReader(proc.inputStream))
            if (rdr.readLine() != null) return true
            rdr.close()
            proc.destroy()
        } catch (_: Exception) {}
        // test-keys build (often custom ROMs)
        if (Build.TAGS?.contains("test-keys", ignoreCase = true) == true) return true
        return false
    }

    // ─── Debugger detection ──────────────────────────────────────

    private fun isDebuggerAttached(): Boolean {
        if (Debug.isDebuggerConnected()) return true
        if (Debug.waitingForDebugger()) return true
        return false
    }

    // ─── Emulator detection ──────────────────────────────────────

    private fun isEmulator(): Boolean {
        val indicators = listOf(
            Build.PRODUCT    to listOf("sdk", "google_sdk", "emulator", "sim"),
            Build.MODEL      to listOf("sdk", "google_sdk", "emulator", "simulator", "android_sdk"),
            Build.MANUFACTURER to listOf("google", "unknown"),
            Build.BRAND      to listOf("google", "generic"),
            Build.DEVICE     to listOf("generic", "generic_x86", "generic_arm64", "generic_arm"),
            Build.HARDWARE   to listOf("goldfish", "ranchu", "emulator"),
            Build.FINGERPRINT to listOf("generic", "sdk_google", "vbox"),
            Build.BOARD      to listOf("goldfish", "ranchu"),
            Build.BOOTLOADER to listOf("goldfish")
        )
        for ((prop, keywords) in indicators) {
            val propVal = prop.lowercase()
            for (kw in keywords) {
                if (propVal.contains(kw)) return true
            }
        }
        // QEMU driver files
        val qemuFiles = arrayOf(
            "/system/lib/libc_malloc_debug_qemu.so",
            "/system/lib/libc_malloc_qemu.so",
            "/system/lib/libc.qemu.so",
            "/system/bin/qemu-props"
        )
        for (f in qemuFiles) {
            if (File(f).exists()) return true
        }
        return false
    }

    // ─── Frida detection ─────────────────────────────────────────

    private fun isFridaPresent(): Boolean {
        // Frida default D-Bus port
        try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", 27042), 50)
            s.close()
            return true
        } catch (_: Exception) {}
        // Frida server process
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
            val rdr = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (rdr.readLine().also { line = it } != null) {
                if (line?.contains("frida-server") == true ||
                    line?.contains("frida-agent") == true ||
                    line?.contains("frida-helper") == true) {
                    rdr.close()
                    proc.destroy()
                    return true
                }
            }
            rdr.close()
            proc.destroy()
        } catch (_: Exception) {}
        // Check for Frida library in /proc/self/maps
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("cat", "/proc/self/maps"))
            val rdr = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (rdr.readLine().also { line = it } != null) {
                if (line?.contains("frida") == true ||
                    line?.contains("gadget") == true ||
                    line?.contains("frida-agent") == true) {
                    rdr.close()
                    proc.destroy()
                    return true
                }
            }
            rdr.close()
            proc.destroy()
        } catch (_: Exception) {}
        return false
    }

    // ─── Code integrity (dex CRC check) ─────────────────────────

    private fun isCodeTampered(ctx: Context): Boolean {
        return try {
            val pm = ctx.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            }
            val appInfo = pkgInfo.applicationInfo ?: return true
            val apkPath = appInfo.sourceDir ?: return true
            val zf = ZipFile(apkPath)
            val entries = zf.entries()
            var classesOk = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    val crc = entry.crc
                    if (crc == -1L) return true
                    classesOk = true  // at least one dex present & readable
                }
            }
            zf.close()
            !classesOk
        } catch (_: Exception) { false }
    }

    // ─── Signature re-verification ───────────────────────────────

    private fun isApkModified(ctx: Context): Boolean {
        val actual = Obf.currentSigningSha(ctx)
        if (actual.isEmpty()) return true
        return !actual.equals(ObfConstants.EXPECTED_SIGNING_SHA256, ignoreCase = true)
    }

    /** Launch the bricked activity from any context. */
    fun brick(ctx: Context) {
        // Write marker
        CrashGate.writeBrickMarker(ctx)
        // Clear prefs
        try {
            com.ghosttype.utils.SettingsStore.prefs(ctx).edit().clear().apply()
            ctx.getSharedPreferences("ghosttype_gate", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
        // Launch brick activity
        try {
            ctx.startActivity(
                android.content.Intent(ctx, com.ghosttype.ui.BrickedActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (_: Exception) {}
        // Kill process after 500ms
        try {
            Thread.sleep(500)
        } catch (_: Exception) {}
        Process.killProcess(Process.myPid())
    }
}
