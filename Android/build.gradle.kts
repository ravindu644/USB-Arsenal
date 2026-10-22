// AGP 9 builds Kotlin itself; applying org.jetbrains.kotlin.android on top of it fails
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
