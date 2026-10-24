import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared versioning (version.properties) + release signing, see gradle/android-common.gradle
apply(from = rootProject.file("gradle/android-common.gradle"))
val appVersionName: String by extra

android {
    namespace = "x.vladgba.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "x.vladgba.keyboard"
        minSdk = 21
        targetSdk = 35
        resValue("string", "app_name", "VKeyboard")
        resValue("string", "version", appVersionName)
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

// Pure Kotlin format checks without adding a test library or an Android runtime dependency.
tasks.register<JavaExec>("verifyDictionaryImport") {
    group = "verification"
    description = "Checks binary dictionary import using synthetic fixtures"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("x.vladgba.keyboard.core.AospDictionaryTestKt")
}
