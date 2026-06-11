package com.ghosttype.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import com.ghosttype.BuildConfig

/**
 * Hard tamper-resistance check that runs once at every cold start of
 * the app and the IME service. The single most important check is
 * signature pinning — if a thief decompiles the APK, edits branding,
 * and re-signs with their own keystore, the actual signing-cert SHA
 * won't match the [ObfConstants.EXPECTED_SIGNING_SHA256] baked in at
 * build time → [verifyOrDie] returns false → the app sits on the lock
 * screen forever and the IME shows a "locked" view instead of the
 * keyboard. Nothing crashes — that would just get GhostType flagged
 * as malware. It just refuses to function.
 *
 * In debug builds we skip the check entirely so dev iteration isn't
 * blocked. In release builds compiled WITHOUT a keystore (the obf
 * generator couldn't compute a real SHA) we also skip — those are
 * for owner-side internal testing only and shouldn't be distributed.
 */
object SecurityGuard {

    /** Returns true if the app is running on a legitimate, signed,
     *  non-debugger-attached install of GhostType. False on any
     *  tamper detection — caller must show the lock screen. */
    fun verifyOrDie(ctx: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        if (!ObfConstants.IS_OBFUSCATED) return true

        // 1. Native library loading — if it fails, app is compromised.
        if (!NativeGuard.ensureLoaded()) return false

        // 2. Cross-verification: native code re-computes signing SHA and
        //    compares it with the expected value. If either the Kotlin
        //    bytecode or the native library was patched, this fails.
        if (!NativeGuard.verify(ctx)) return false

        // 3. Signature pinning (Kotlin side) — still kept so there are
        //    TWO independent checks that must both pass.
        val actual = Obf.currentSigningSha(ctx)
        if (actual.isEmpty()) return false
        if (!actual.equals(ObfConstants.EXPECTED_SIGNING_SHA256, ignoreCase = true)) {
            return false
        }

        // 4. Production build must NOT carry FLAG_DEBUGGABLE — that
        //    would let anyone attach jdb / Frida and dump memory.
        val info = ctx.applicationInfo
        if ((info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false

        // 5. Debugger check done in native (harder to hook).
        if (NativeGuard.isDebuggerAttached()) return false

        // 6. Kotlin-side debugger check as backup.
        if (Debug.isDebuggerConnected()) return false

        return true
    }
}
