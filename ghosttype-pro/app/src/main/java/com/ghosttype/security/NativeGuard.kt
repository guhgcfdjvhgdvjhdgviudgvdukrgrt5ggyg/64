package com.ghosttype.security

import android.content.Context

/**
 * JNI/NDK native security checks. These run in C++ so they're much
 * harder to find and patch than equivalent Kotlin/Java bytecode.
 *
 * The native library also performs cross-verification: it re-computes
 * the signing SHA256 independently and compares it with the expected
 * value, so even if someone patches the Kotlin Obf/ObfConstants the
 * native check will still catch them.
 */
internal object NativeGuard {

    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("nativeguard")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        return loaded
    }

    /** Returns true if loaded. Kotlin callers must check this first. */
    fun isLoaded(): Boolean = loaded

    // ── Native methods ──────────────────────────────────────────

    /**
     * Computes the APK signing SHA256 in native code and compares
     * with [expectedSha]. Returns true only if they match.
     */
    private external fun verifySigningSha(ctx: Context, expectedSha: String): Boolean

    /**
     * Secondary check — verifies that an obfuscated constant hasn't
     * been tampered with, by computing the SHA in native and comparing.
     */
    private external fun verifyObfuscatedConstant(ctx: Context, constantValue: String): Boolean

    /** Debugger check done in native to prevent simple hooking. */
    private external fun isDebuggerAttachedNative(): Boolean

    // ── Public wrappers ─────────────────────────────────────────

    fun verify(ctx: Context): Boolean {
        if (!loaded) return false
        return try {
            val expected = ObfConstants.EXPECTED_SIGNING_SHA256
            if (expected.isEmpty() || expected == "0".repeat(64)) return true
            verifySigningSha(ctx, expected)
        } catch (e: Exception) {
            false
        }
    }

    fun verifyObfConstant(ctx: Context, constantValue: String): Boolean {
        if (!loaded) return false
        return try {
            verifyObfuscatedConstant(ctx, constantValue)
        } catch (e: Exception) {
            false
        }
    }

    fun isDebuggerAttached(): Boolean {
        if (!loaded) return false
        return try {
            isDebuggerAttachedNative()
        } catch (e: Exception) {
            false
        }
    }
}
