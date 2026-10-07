import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Versioning: single source of truth in /version.properties ----
val versionFile = rootProject.file("version.properties")
fun readVersion(): Triple<Int, Int, Int> {
    val p = Properties().apply { FileInputStream(versionFile).use { load(it) } }
    fun part(k: String) = p.getProperty(k)?.trim()?.toIntOrNull() ?: error("version.properties: '$k' must be a number")
    return Triple(part("major"), part("minor"), part("patch"))
}
val (verMajor, verMinor, verPatch) = readVersion()
require(verMinor in 0..99 && verPatch in 0..99) { "version.properties: minor and patch must be 0..99" }
val appVersionName = "$verMajor.$verMinor.$verPatch"
val appVersionCode = verMajor * 10000 + verMinor * 100 + verPatch

// Release signing is optional: without keystore.properties the release build is simply unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}
val hasKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "x.vladgba.keyboard"
    compileSdk = 35

    signingConfigs {
        if (hasKeystore) create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "x.vladgba.keyboard"
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", "VKeyboard")
        resValue("string", "version", appVersionName)
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "VKeyb $appVersionName dev")
            resValue("string", "version", "$appVersionName-debug")
        }
    }

    buildFeatures {
        resValues = true
        buildConfig = false
    }

    // Google Play (.aab): each user downloads only the strings for their device languages and the
    // icon for their screen density. The in-app language list shows only languages actually
    // installed (see AppLocale.isAvailable), so it stays correct with split languages.
    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    // Don't embed the (empty) dependency report in the APK/AAB signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Smaller dex: drop Kotlin's runtime null-check calls on parameters and Java return values.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

// No library dependencies on purpose: the app uses only the Android framework + Kotlin stdlib.
dependencies { }

// ---- Version bump tasks: gradlew bumpPatch / bumpMinor / bumpMajor ----
fun bump(kind: String) {
    val (ma, mi, pa) = readVersion()
    val (nMa, nMi, nPa) = when (kind) {
        "major" -> Triple(ma + 1, 0, 0)
        "minor" -> Triple(ma, mi + 1, 0)
        else -> Triple(ma, mi, pa + 1)
    }
    require(nMi < 100 && nPa < 100) { "minor/patch would reach 100: bump the next part instead" }
    val text = versionFile.readText()
        .replace(Regex("(?m)^major=.*$"), "major=$nMa")
        .replace(Regex("(?m)^minor=.*$"), "minor=$nMi")
        .replace(Regex("(?m)^patch=.*$"), "patch=$nPa")
    versionFile.writeText(text)
    println("Version $ma.$mi.$pa -> $nMa.$nMi.$nPa (versionCode ${nMa * 10000 + nMi * 100 + nPa})")
}
listOf("Major", "Minor", "Patch").forEach { k ->
    tasks.register("bump$k") {
        group = "versioning"
        description = "Increments the ${k.lowercase()} version in version.properties"
        notCompatibleWithConfigurationCache("edits version.properties")
        doLast { bump(k.lowercase()) }
    }
}
tasks.register("printVersion") {
    group = "versioning"
    doLast { println("$appVersionName ($appVersionCode)") }
}
