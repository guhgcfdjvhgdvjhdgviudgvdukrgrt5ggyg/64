package com.ghosttype.security

import android.content.Context
import android.util.Base64

/**
 * JNI/NDK native security core. ALL sensitive operations (XOR
 * decryption, signing SHA, pastebin ID verification) run in C++.
 *
 * If someone removes nativeguard.so from the APK, everything breaks:
 *   - Obf.decode() returns garbage → pastebin URLs are wrong → gates
 *     can't fetch → brick
 *   - Signing SHA can't be verified → SecurityGuard/Hardener fail → brick
 *   - Branding text, license, space label all become garbage
 *
 * The app literally CANNOT function without this native library.
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

    fun isLoaded(): Boolean = loaded

    // ── Native methods (core decryption — app DEPENDS on these) ──

    /** XOR-decrypt using keystore-derived key. Returns "" on failure. */
    external fun nativeDecrypt(ctx: Context, encryptedB64: String): String

    /** Returns the APK signing SHA256 (native computation). */
    external fun nativeCurrentSigningSha(ctx: Context): String

    /** Native signing SHA verification. */
    private external fun verifySigningSha(ctx: Context, expectedSha: String): Boolean

    /** Native pastebin ID HMAC verification. */
    external fun nativeVerifyPastebinIds(
        ctx: Context,
        approvalUrl: String,
        crashUrl: String,
        updateUrl: String,
        expectedHmac: String,
        saltEncrypted: String
    ): Boolean

    /** Debugger check in native code. */
    external fun isDebuggerAttachedNative(): Boolean

    /** Native package name verification — prevents repackaging with different app ID. */
    external fun nativeVerifyPackageName(ctx: Context, expectedPkg: String): Boolean

    /** Native DEX integrity check — verifies CRC of each classes*.dex against build-time values. */
    external fun nativeVerifyDexIntegrity(ctx: Context, dexCrcMap: String): Boolean

    /** Native quick integrity check — lightweight package name + signing SHA combo. */
    external fun nativeQuickVerify(ctx: Context, expectedPkg: String, expectedSha: String): Boolean

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

    fun verifyPastebinIds(ctx: Context): Boolean {
        if (!loaded) return false
        return try {
            // Decrypt URLs and get constants — all via native XOR
            val approvalUrl = Obf.decode(ctx, ObfConstants.APPROVAL_URL)
            val crashUrl    = Obf.decode(ctx, ObfConstants.CRASH_URL)
            val updateUrl   = Obf.decode(ctx, ObfConstants.UPDATE_URL)
            if (approvalUrl.isBlank() || crashUrl.isBlank() || updateUrl.isBlank()) return false
            nativeVerifyPastebinIds(
                ctx, approvalUrl, crashUrl, updateUrl,
                ObfConstants.PASTEBIN_IDS_HMAC,
                ObfConstants.PASTEBIN_SALT_ENCRYPTED
            )
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

    /** Verifies that the app's package name hasn't been changed (prevents repackaging under a different ID). */
    fun verifyPackageName(ctx: Context): Boolean {
        if (!loaded) return false
        return try {
            nativeVerifyPackageName(ctx, ObfConstants.EXPECTED_PACKAGE_NAME)
        } catch (e: Exception) {
            false
        }
    }

    /** Verifies DEX file integrity by comparing CRC32 against build-time values. */
    fun verifyDexIntegrity(ctx: Context): Boolean {
        if (!loaded) return false
        return try {
            nativeVerifyDexIntegrity(ctx, ObfConstants.DEX_CRC_MAP)
        } catch (e: Exception) {
            false
        }
    }

    /** Lightweight integrity check — verifies package name + signing SHA in native code.
     *  Designed to be called from multiple scattered locations so an attacker can't bypass
     *  everything by patching a single check point. */
    fun quickVerify(ctx: Context): Boolean {
        if (!loaded) return false
        return try {
            nativeQuickVerify(ctx, ObfConstants.EXPECTED_PACKAGE_NAME, ObfConstants.EXPECTED_SIGNING_SHA256)
        } catch (e: Exception) {
            false
        }
    }
}
