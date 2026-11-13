import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release keystore lives outside the repository. local.properties (not committed) points to it:
//   keystore.dir=C:/path/to/folder   (holding keystore.properties and the .jks file)
val keystoreDir: File = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}.getProperty("keystore.dir")?.let { file(it) } ?: rootProject.file("keystore")

val signingProps = Properties().apply {
    val file = File(keystoreDir, "keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "tv.nuvio.installer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tv.nuvio.installer.tizen"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.5"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = File(keystoreDir, signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
