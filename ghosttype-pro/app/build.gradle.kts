import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ===================== RELEASE SIGNING =====================
// Reads keystore info in this priority order:
//   1) Environment variables (used by GitHub Actions — see
//      .github/workflows/main.yml). The workflow decodes
//      KEYSTORE_BASE64 into  app/keystore/ghosttype-release.jks  and
//      exports KEYSTORE_PATH + the three passwords/alias.
//   2) keystore.properties file at project root (for local signed
//      builds). File is gitignored. Format:
//          storeFile=keystore/ghosttype-release.jks
//          storePassword=...
//          keyAlias=...
//          keyPassword=...
//   3) None — release build is unsigned (debug build still works).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

fun envOrProp(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProps.getProperty(propKey)

val signStorePath  = envOrProp("KEYSTORE_PATH",     "storeFile")
val signStorePass  = envOrProp("KEYSTORE_PASSWORD", "storePassword")
val signKeyAlias   = envOrProp("KEY_ALIAS",         "keyAlias")
val signKeyPass    = envOrProp("KEY_PASSWORD",      "keyPassword")

val canSignRelease = !signStorePath.isNullOrBlank() &&
        !signStorePass.isNullOrBlank() &&
        !signKeyAlias.isNullOrBlank() &&
        !signKeyPass.isNullOrBlank() &&
        rootProject.file(signStorePath!!).exists()

// ===================== OBFUSCATED CONSTANTS GEN (v1.9) =====================
// Generates `ObfConstants.kt` at build time containing:
//   - EXPECTED_SIGNING_SHA256 — read from the actual release keystore
//     so SecurityGuard can pin against it at runtime. No human paste
//     step required.
//   - APPROVAL_URL, WHATSAPP_NUMBER, OWNER_NAME, etc. — XOR-encrypted
//     with a key derived from packageName + cert SHA. A thief who
//     re-signs the APK with their own keystore produces a different
//     derived key → every Obf.decode() returns garbage → app + IME
//     are stuck on the lock screen for the rest of that APK's life.
//
// For unsigned / debug builds (no keystore), we still generate the
// file but with IS_OBFUSCATED=false and plain-text values so dev
// iteration isn't blocked. SecurityGuard skips its checks in that
// mode so the keyboard remains usable.
val obfOutputDir = layout.buildDirectory.dir("generated/source/obf").get().asFile

fun computeKeystoreSha(jks: File, password: String, alias: String): String {
    return try {
        val ks = KeyStore.getInstance("JKS")
        jks.inputStream().use { stream -> ks.load(stream, password.toCharArray()) }
        val cert = ks.getCertificate(alias) ?: return ""
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { b -> "%02x".format(b) }
    } catch (e: Exception) {
        println("[GhostType obf] Failed to read keystore: ${e.message}")
        ""
    }
}

fun xorB64(plain: String, key: ByteArray): String {
    val pBytes = plain.toByteArray(Charsets.UTF_8)
    val out = ByteArray(pBytes.size)
    for (i in pBytes.indices) {
        out[i] = (pBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
    }
    return Base64.getEncoder().encodeToString(out)
}

val generateObfConstants = tasks.register("generateObfConstants") {
    group = "ghosttype"
    description = "Generates encrypted constants bound to the release signing certificate."

    val secretsFile = project.file("secrets.properties")
    inputs.property("storePath", signStorePath ?: "none")
    inputs.property("alias", signKeyAlias ?: "none")
    inputs.property("canSign", canSignRelease)
    inputs.file(secretsFile)
    outputs.dir(obfOutputDir)

    doLast {
        val pkgName = "com.ghosttype"
        if (!secretsFile.exists()) {
            throw GradleException("secrets.properties not found at $secretsFile — copy secrets.properties.example and fill in the values")
        }
        val secrets = Properties().apply {
            load(secretsFile.inputStream())
        }
        val requiredKeys = listOf(
            "APPROVAL_URL", "CRASH_URL", "UPDATE_URL",
            "WHATSAPP_NUMBER", "OWNER_NAME", "OWNER_TEAM",
            "INSTAGRAM_URL", "WA_CHANNEL_URL", "WA_COMMUNITY_URL",
            "TELEGRAM_URL", "LICENSE_LINE", "SPACE_LABEL"
        )
        val missing = requiredKeys.filter { secrets.getProperty(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing required secrets in secrets.properties: $missing")
        }
        val plaintexts = linkedMapOf(
            "APPROVAL_URL"     to secrets.getProperty("APPROVAL_URL"),
            "CRASH_URL"        to secrets.getProperty("CRASH_URL"),
            "UPDATE_URL"       to secrets.getProperty("UPDATE_URL"),
            "WHATSAPP_NUMBER"  to secrets.getProperty("WHATSAPP_NUMBER"),
            "OWNER_NAME"       to secrets.getProperty("OWNER_NAME"),
            "OWNER_TEAM"       to secrets.getProperty("OWNER_TEAM"),
            "INSTAGRAM_URL"    to secrets.getProperty("INSTAGRAM_URL"),
            "WA_CHANNEL_URL"   to secrets.getProperty("WA_CHANNEL_URL"),
            "WA_COMMUNITY_URL" to secrets.getProperty("WA_COMMUNITY_URL"),
            "TELEGRAM_URL"     to secrets.getProperty("TELEGRAM_URL"),
            "LICENSE_LINE"     to secrets.getProperty("LICENSE_LINE"),
            "SPACE_LABEL"      to secrets.getProperty("SPACE_LABEL")
        )

        val sha: String
        val isReal: Boolean
        if (canSignRelease) {
            val computed = computeKeystoreSha(
                rootProject.file(signStorePath!!),
                signStorePass!!,
                signKeyAlias!!
            )
            if (computed.isNotEmpty()) {
                sha = computed
                isReal = true
                println("[GhostType obf] Bound constants to keystore SHA-256: $sha")
            } else {
                sha = "0".repeat(64)
                isReal = false
                println("[GhostType obf] Could not read keystore — falling back to plaintext mode.")
            }
        } else {
            sha = "0".repeat(64)
            isReal = false
            println("[GhostType obf] No keystore configured — generating plaintext (dev) constants.")
        }

        val seed = "ghosttype_obf_v1::$pkgName::$sha".toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(seed)

        val emitted = if (isReal) {
            plaintexts.mapValues { (_, v) -> xorB64(v, key) }
        } else {
            // Skip encryption in dev mode so Obf.decode (which short-circuits
            // when IS_OBFUSCATED == false) returns usable plaintext values.
            plaintexts
        }

        val pkgDir = File(obfOutputDir, "com/ghosttype/security")
        pkgDir.mkdirs()
        File(pkgDir, "ObfConstants.kt").writeText(buildString {
            append("// Auto-generated by Gradle task generateObfConstants. Do not edit.\n")
            append("package com.ghosttype.security\n\n")
            append("internal object ObfConstants {\n")
            append("    const val EXPECTED_SIGNING_SHA256: String = \"$sha\"\n")
            append("    const val IS_OBFUSCATED: Boolean = $isReal\n")
            for ((k, v) in emitted) {
                append("    const val $k: String = \"")
                append(v.replace("\\", "\\\\").replace("\"", "\\\""))
                append("\"\n")
            }
            append("}\n")
        })
    }
}

android {
    namespace = "com.ghosttype"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.ghosttype"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.12"
        vectorDrawables { useSupportLibrary = true }
    }

    sourceSets {
        getByName("main") {
            // Hook the generated ObfConstants.kt into the main source set
            // so kotlinc / KSP / javac all pick it up alongside hand-written code.
            // Using `generateObfConstants.map { obfOutputDir }` (a Provider)
            // — instead of a bare File — propagates the implicit task
            // dependency to every consumer (including KSP), so Gradle 8.7+
            // strict task-dependency validation passes without needing
            // separate `dependsOn(...)` wiring for each compile/ksp task.
            java.srcDir(generateObfConstants.map { obfOutputDir })
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(signStorePath!!)
                storePassword = signStorePass
                keyAlias = signKeyAlias
                keyPassword = signKeyPass
            }
        }
    }

    buildTypes {
        release {
            // v1.9 — R8 + resource shrinking enabled. proguard-rules.pro
            // keeps the Android-loaded entry points + Room reflection
            // surfaces + Compose runtime + the security module's exposed
            // APIs intact while obfuscating everything else.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // v1.10 — INSTALLABILITY FIX. Earlier versions left the release
            // APK unsigned when no release keystore secrets were configured
            // on the GitHub Actions runner — Android then refused to install
            // it with the infamous "App not installed — package appears to
            // be invalid" error (modern Android requires APK Signature
            // Scheme v2/v3, which an unsigned APK doesn't satisfy).
            //
            // Now we ALWAYS attach a signing config: real release keystore
            // when available, otherwise fall back to the auto-generated
            // debug keystore so the artifact is at least signature-valid
            // and side-loadable. SecurityGuard will detect the debug-cert
            // SHA mismatch with the obfuscation-derived expected SHA and
            // skip its strict pinning (IS_OBFUSCATED is false in that path
            // — see generateObfConstants), so the keyboard stays usable in
            // dev/CI builds without a real signing key.
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                println("[GhostType] No release keystore configured — release APK will be signed with the DEBUG key so it's still installable. Configure KEYSTORE_BASE64 secret in GitHub Actions for a production build.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // NDK / native C++ security checks — compiled by CMake.
    // The library lives at app/src/main/cpp/ and provides JNI-based
    // signing-SHA verification that is much harder to reverse-engineer
    // than the equivalent Kotlin bytecode.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // Build native lib for the three most common ABIs.
    // x86/x86_64 are omitted because the emulators that use them are
    // already blocked by Hardener.isEmulator().
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// ===================== TASK WIRING =====================
// Primary wiring is via the `srcDir(generateObfConstants.map { ... })`
// Provider in the android.sourceSets block above — that propagates the
// task dependency to every consumer (kotlinc, javac, KSP, lint, etc.)
// automatically and satisfies Gradle 8.7+ strict task-dependency
// validation.
//
// This afterEvaluate is a belt-and-suspenders fallback that also wires
// any compile* / ksp* / lint* task to depend on generateObfConstants,
// in case a future AGP/KSP version stops honouring the source-set
// Provider chain for some task type.
afterEvaluate {
    tasks.matching { t ->
        val n = t.name
        (n.startsWith("compile") &&
            (n.contains("Kotlin") || n.contains("JavaWith") || n.contains("Java"))) ||
            n.startsWith("ksp") ||
            n.startsWith("lint")
    }.configureEach { dependsOn(generateObfConstants) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.android.material:material:1.12.0")

    // v1.9 — used by ApprovalGate to fetch the GitHub-hosted Users.json.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coil — URL image loading for Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // AdMob — Home banner ads
    implementation("com.google.android.gms:play-services-ads:23.2.0")
}
