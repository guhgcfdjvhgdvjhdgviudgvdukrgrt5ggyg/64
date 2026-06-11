#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include <android/log.h>

#define LOG_TAG "NativeGuard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Derive key same way as Kotlin Obf.kt
static std::string sha256_hex(JNIEnv *env, const jbyteArray &input) {
    jclass mdCls = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(mdCls, "getInstance",
        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jobject md = env->CallStaticObjectMethod(mdCls, getInstance,
        env->NewStringUTF("SHA-256"));

    jmethodID digest = env->GetMethodID(mdCls, "digest", "([B)[B");
    jbyteArray hashBytes = (jbyteArray)env->CallObjectMethod(md, digest, input);

    jsize len = env->GetArrayLength(hashBytes);
    jbyte *bytes = env->GetByteArrayElements(hashBytes, nullptr);

    std::string hex;
    char buf[3];
    for (jsize i = 0; i < len; i++) {
        unsigned char c = (unsigned char)bytes[i];
        snprintf(buf, sizeof(buf), "%02x", c);
        hex += buf;
    }
    env->ReleaseByteArrayElements(hashBytes, bytes, JNI_ABORT);
    env->DeleteLocalRef(hashBytes);
    env->DeleteLocalRef(md);
    env->DeleteLocalRef(mdCls);
    return hex;
}

static std::string getSigningShaNative(JNIEnv *env, jobject ctx) {
    jclass ctxCls = env->GetObjectClass(ctx);
    jmethodID getPm = env->GetMethodID(ctxCls, "getPackageManager",
        "()Landroid/content/pm/PackageManager;");
    jobject pm = env->CallObjectMethod(ctx, getPm);

    jmethodID getPkgName = env->GetMethodID(ctxCls, "getPackageName",
        "()Ljava/lang/String;");
    jstring pkgStr = (jstring)env->CallObjectMethod(ctx, getPkgName);
    const char *pkgChars = env->GetStringUTFChars(pkgStr, nullptr);

    jclass pmCls = env->FindClass("android/content/pm/PackageManager");
    jmethodID getPkgInfo = env->GetMethodID(pmCls, "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");

    jint GET_SIGNATURES = 0x00000040;
    jobject pkgInfo = env->CallObjectMethod(pm, getPkgInfo, pkgStr, GET_SIGNATURES);

    env->ReleaseStringUTFChars(pkgStr, pkgChars);

    if (pkgInfo == nullptr) {
        LOGE("getSigningShaNative: getPackageInfo returned null");
        return "";
    }

    // Get signatures array (compatible with all API levels)
    jclass pkgInfoCls = env->FindClass("android/content/pm/PackageInfo");
    jfieldID sigsField = env->GetFieldID(pkgInfoCls, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray sigs = (jobjectArray)env->GetObjectField(pkgInfo, sigsField);

    if (sigs == nullptr || env->GetArrayLength(sigs) == 0) {
        LOGE("getSigningShaNative: no signatures found");
        return "";
    }

    jobject firstSig = env->GetObjectArrayElement(sigs, 0);
    jclass sigCls = env->FindClass("android/content/pm/Signature");
    jmethodID toByteArray = env->GetMethodID(sigCls, "toByteArray", "()[B");
    jbyteArray certBytes = (jbyteArray)env->CallObjectMethod(firstSig, toByteArray);

    std::string sha = sha256_hex(env, certBytes);

    env->DeleteLocalRef(certBytes);
    env->DeleteLocalRef(firstSig);
    env->DeleteLocalRef(sigs);
    env->DeleteLocalRef(pkgInfo);
    env->DeleteLocalRef(pkgInfoCls);
    env->DeleteLocalRef(pmCls);
    env->DeleteLocalRef(pm);
    env->DeleteLocalRef(ctxCls);

    return sha;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ghosttype_security_NativeGuard_verifySigningSha(
    JNIEnv *env, jclass /*clazz*/, jobject ctx, jstring expectedSha) {

    if (ctx == nullptr || expectedSha == nullptr) {
        LOGE("verifySigningSha: null arguments");
        return JNI_FALSE;
    }

    const char *expected = env->GetStringUTFChars(expectedSha, nullptr);
    std::string actual = getSigningShaNative(env, ctx);

    if (actual.empty()) {
        LOGE("verifySigningSha: could not compute signing SHA");
        env->ReleaseStringUTFChars(expectedSha, expected);
        return JNI_FALSE;
    }

    // Case-insensitive comparison
    bool match = (strcasecmp(expected, actual.c_str()) == 0);

    if (!match) {
        LOGI("verifySigningSha: MISMATCH expected=%s actual=%s", expected, actual.c_str());
    }

    env->ReleaseStringUTFChars(expectedSha, expected);
    return match ? JNI_TRUE : JNI_FALSE;
}

// Secondary check: verify that the Kotlin Obfuscated class is intact
// by computing the signing SHA in native and comparing with the
// value from ObfConstants. If ObfConstants was tampered, mismatch.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ghosttype_security_NativeGuard_verifyObfuscatedConstant(
    JNIEnv *env, jclass /*clazz*/, jobject ctx, jstring constantValue) {

    if (ctx == nullptr || constantValue == nullptr) return JNI_FALSE;

    const char *expected = env->GetStringUTFChars(constantValue, nullptr);
    std::string actual = getSigningShaNative(env, ctx);

    if (actual.empty()) {
        env->ReleaseStringUTFChars(constantValue, expected);
        return JNI_FALSE;
    }

    bool match = (strcasecmp(expected, actual.c_str()) == 0);
    env->ReleaseStringUTFChars(constantValue, expected);
    return match ? JNI_TRUE : JNI_FALSE;
}

// Environment sanity check done in native
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ghosttype_security_NativeGuard_isDebuggerAttachedNative(JNIEnv *env, jclass /*clazz*/) {
    jclass debugCls = env->FindClass("android/os/Debug");
    jmethodID isConnected = env->GetStaticMethodID(debugCls, "isDebuggerConnected", "()Z");
    jboolean connected = env->CallStaticBooleanMethod(debugCls, isConnected);
    env->DeleteLocalRef(debugCls);
    return connected;
}
