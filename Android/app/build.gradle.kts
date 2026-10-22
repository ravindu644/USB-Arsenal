plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// UA_VERSION is the only version source; versionCode matches `make bump` and module.prop
val uaVersion = Regex("""#define\s+UA_VERSION\s+"(\d+)\.(\d+)\.(\d+)"""")
    .find(rootProject.file("../src/include/usbarsenal.h").readText())
    ?.groupValues?.drop(1)?.map(String::toInt)
    ?: error("UA_VERSION x.y.z not found in src/include/usbarsenal.h")

android {
    namespace = "com.usbarsenal.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.usbarsenal.app"
        minSdk = 26
        targetSdk = 37
        versionName = uaVersion.joinToString(".")
        versionCode = uaVersion[0] * 1000 + uaVersion[1] * 100 + uaVersion[2] * 10
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
