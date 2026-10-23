import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Firebase plugins are applied only when app/google-services.json exists, so a build without it still works (Crashlytics then stays inactive).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

fun props(name: String) = Properties().apply {
    val f = rootProject.file(name); if (f.exists()) f.inputStream().use { load(it) }
}
val local = props("local.properties")        // NOT committed
val signing = props("keystore.properties")   // NOT committed

android {
    namespace = "com.filemind.smartmanager"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.filemind.smartmanager"
        minSdk = 26
        targetSdk = 35
        // Overridable from CI: -PappVersionCode=N -PappVersionName=X.Y.Z (release workflow). Defaults keep local builds unchanged.
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
        // Start.io App ID: put startio.appId=... in local.properties. Empty => ads disabled.
        // Start.io App ID is not a secret (it ships inside the app). Order: local.properties > env > project default. Empty value disables ads.
        val startioId = local.getProperty("startio.appId") ?: System.getenv("STARTIO_APP_ID") ?: "209933420"
        buildConfigField("String", "STARTIO_APP_ID", "\"$startioId\"")
    }
    packaging {
        resources {
            // Several Apache jars carry the same license/notice files; keep one. Multi-release folders are unused on Android.
            pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
            excludes += "META-INF/versions/**"
        }
    }
    signingConfigs {
        // Shared DEBUG key (public on purpose, NOT secret, never used for Play): CI would otherwise sign every debug build with a new random key,
        // and Android refuses to install a build over one signed with a different key.
        getByName("debug") {
            storeFile = file("filemind-debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
        if (signing.containsKey("storeFile")) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Start.io SDK pinned to 5.3.0: 5.4.0 (what "5.+" resolved to) failed checkDebugAarMetadata on our toolchain (compileSdk 35 / AGP 8.5.2).
    // Move to a newer SDK only together with a compileSdk/AGP/Gradle upgrade.
    implementation("com.startapp:inapp-sdk:5.3.0")
    // Read-only 7Z/RAR listing (plain JARs, not AARs). Versions checked on Maven Central / project docs.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")   // needed by commons-compress for LZMA/LZMA2 (the usual 7z codec)
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Crashlytics only (no Analytics). BoM controls versions.
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
