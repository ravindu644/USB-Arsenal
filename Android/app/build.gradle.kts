import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The signing key's passwords, as Droidspaces keeps them: local.properties (never committed),
// then a -P property, then the environment (CI's secrets). An unset GitHub secret arrives as an
// empty string, so a blank counts as missing
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String? =
    sequenceOf(local.getProperty(name), providers.gradleProperty(name).orNull, System.getenv(name))
        .firstOrNull { !it.isNullOrBlank() }

// UA_VERSION is the only version source; versionCode matches the module.prop `make module` writes
val uaVersion = Regex("""#define\s+UA_VERSION\s+"(\d+)\.(\d+)\.(\d+)"""")
    .find(rootProject.file("../src/include/usbarsenal.h").readText())
    ?.groupValues?.drop(1)?.map(String::toInt)
    ?: error("UA_VERSION x.y.z not found in src/include/usbarsenal.h")

// Every commit gets its own versionCode, as the Makefile gives module.prop, so CI builds
// between bumps install over each other. A shallow clone counts 1, a tarball 0
val commits = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

android {
    namespace = "com.usbarsenal.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.usbarsenal.app"
        minSdk = 26
        targetSdk = 37
        versionName = uaVersion.joinToString(".")
        // ponytail: 10000 commits per release before the count spills into the next version's codes
        versionCode = (uaVersion[0] * 1000 + uaVersion[1] * 100 + uaVersion[2] * 10) * 10000 + commits
    }

    // Droidspaces' key at the repo root, gitignored. Without it (a fork, or CI with no secrets) the
    // release is signed with the debug key, so it still builds and installs. With it, debug builds
    // carry it too, as in Droidspaces: one signature, so either installs over the other
    val keystore = rootProject.file("../droidspaces.keystore")
    val password = secret("KEYSTORE_PASSWORD")
    val signed = keystore.exists() && !password.isNullOrEmpty()
    signingConfigs {
        if (signed) {
            val alias = secret("KEY_ALIAS") ?: "droidspaces"
            val keyPassword = secret("KEY_PASSWORD") ?: password
            listOf(getByName("debug"), create("release")).forEach {
                it.storeFile = keystore
                it.storePassword = password
                it.keyAlias = alias
                it.keyPassword = keyPassword
                it.enableV2Signing = true
                it.enableV3Signing = true
                it.enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signed) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The backend the app installs: `make module` builds the four binaries and the boot module
val repo = rootProject.layout.projectDirectory.dir("..")
val buildBackend = tasks.register<Exec>("buildBackend") {
    workingDir(repo)
    commandLine("make", "module")
    inputs.dir(repo.dir("src"))
    inputs.dir(repo.dir("module"))
    inputs.file(repo.file("Makefile"))
    outputs.dir(repo.dir("output/module"))
}

// assets/binaries/usb-arsenal-<arch> and assets/boot-module/, generated so they never show up as a diff.
// customize.sh stays behind: the app does its job itself when it installs the module
abstract class BackendAssets : DefaultTask() {
    @get:InputFiles
    abstract val module: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val dir = module.singleFile
        fs.sync {
            from(dir.resolve("bin")) { into("binaries") }
            from(dir) {
                include("module.prop", "uninstall.sh", "sepolicy.rule")
                into("boot-module")
            }
            into(outputDir)
        }
    }
}

val backendAssets = tasks.register<BackendAssets>("backendAssets") {
    module.from(buildBackend)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(backendAssets, BackendAssets::outputDir)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM; the real one runs the parser tests
    testImplementation("org.json:json:20260814")
}
