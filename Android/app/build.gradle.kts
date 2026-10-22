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
                include("module.prop", "service.sh", "uninstall.sh", "sepolicy.rule")
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
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM; the real one runs the parser tests
    testImplementation("org.json:json:20260814")
}
