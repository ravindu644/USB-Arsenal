package com.usbarsenal.app.backend

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// Ported from Droidspaces' BinaryInstaller, ModuleInstaller, DeviceArch and DroidspacesChecker
private const val MODULE = "/data/adb/modules/usb-arsenal"
private const val MODULE_BIN = "$MODULE/system/bin/usb-arsenal"
private val MODULE_FILES = listOf("module.prop", "service.sh", "uninstall.sh", "sepolicy.rule")

// The binary suffix for this device's primary ABI, or null when we ship none for it
fun deviceArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
    "arm64-v8a" -> "aarch64"
    "armeabi-v7a", "armeabi" -> "armhf"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> null
}

// NO_ROOT: su is missing, denied, or its grant dialog is still open, so nothing could be checked
enum class BackendState { NO_ROOT, NOT_INSTALLED, MODULE_MISSING, CORRUPTED, UPDATE_AVAILABLE, READY }

suspend fun backendState(context: Context): BackendState {
    val r = Root.read(
        """
        test -x $MODULE_BIN && echo bin
        test -f $MODULE/module.prop && echo module
        $UA version >/dev/null 2>&1 && echo runs
        md5sum $MODULE_BIN 2>/dev/null
        """.trimIndent(),
    )
    val installed = r.out.firstNotNullOfOrNull { Regex("^([0-9a-f]{32}) ").find(it)?.groupValues?.get(1) }
    return when {
        r.code == NO_ROOT || (r.timedOut && "bin" !in r.out) -> BackendState.NO_ROOT
        "bin" !in r.out -> BackendState.NOT_INSTALLED
        "module" !in r.out -> BackendState.MODULE_MISSING
        "runs" !in r.out -> BackendState.CORRUPTED
        installed != assetMd5(context) -> BackendState.UPDATE_AVAILABLE
        else -> BackendState.READY
    }
}

private suspend fun assetMd5(context: Context): String? {
    val arch = deviceArch() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("binaries/usb-arsenal-$arch").use { input ->
                val md = MessageDigest.getInstance("MD5")
                val buf = ByteArray(8192)
                while (true) md.update(buf, 0, input.read(buf).takeIf { it > 0 } ?: break)
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }
}

/*
 * What the module zip's customize.sh does, so a flashed phone and an app-installed one are the
 * same: the binary in the module's system/bin, the workspace's bin/ linked to it, then the
 * daemon restarted on the new binary. Each step reports a line for the install terminal.
 */
suspend fun installBackend(context: Context, log: (String) -> Unit): Boolean = installing.withLock { install(context, log) }

// One install at a time: a second one would rewrite the staged binary while the first copies it
private val installing = Mutex()

private suspend fun install(context: Context, log: (String) -> Unit): Boolean {
    val arch = deviceArch() ?: run {
        log("[-] No usb-arsenal binary for ${Build.SUPPORTED_ABIS.firstOrNull()}")
        return false
    }
    log("[+] Device architecture: $arch")

    // Root reads them from the app's cache; the binary rename below dodges "text file busy" on a live daemon
    val staging = File(context.cacheDir, "backend")
    try {
        withContext(Dispatchers.IO) {
            staging.deleteRecursively()
            staging.mkdirs()
            context.assets.open("binaries/usb-arsenal-$arch").use { i -> File(staging, "usb-arsenal").outputStream().use(i::copyTo) }
            for (f in MODULE_FILES) {
                context.assets.open("boot-module/$f").use { i -> File(staging, f).outputStream().use(i::copyTo) }
            }
        }
        val s = shellQuote(staging.absolutePath)

        log("[+] Installing the boot module in $MODULE")
        // A remove or disable mark from the manager, or a zip still pending in modules_update,
        // would undo this install at the next boot
        Root.change(
            """
            rm -rf $MODULE/remove $MODULE/disable /data/adb/modules_update/usb-arsenal &&
            mkdir -p $MODULE/system/bin $WORKSPACE/bin &&
            for f in ${MODULE_FILES.joinToString(" ")}; do cp $s/${'$'}f $MODULE/${'$'}f || exit 1; done &&
            chmod 755 $MODULE/service.sh $MODULE/uninstall.sh &&
            chmod 644 $MODULE/module.prop $MODULE/sepolicy.rule &&
            cp $s/usb-arsenal $MODULE_BIN.new && chmod 755 $MODULE_BIN.new &&
            chcon u:object_r:system_file:s0 $MODULE_BIN.new && sync &&
            mv -f $MODULE_BIN.new $MODULE_BIN && sync &&
            ln -sfn $MODULE_BIN $UA
            """.trimIndent(),
        ).also { if (!it.ok) return fail(log, it) }

        // The module's rule applies at boot; load it now too, so dvd mode works before a reboot
        Root.change(
            "rule=\"$(grep -v '^#' $MODULE/sepolicy.rule)\"; " +
                "/data/adb/ksud sepolicy patch \"\$rule\" || magiskpolicy --live \"\$rule\"",
        ).also { log(if (it.ok) "[+] SELinux rule loaded" else "[!] SELinux rule not loaded; it applies at the next boot") }

        log("[+] Starting the daemon")
        val daemon = Root.change("$UA daemon stop >/dev/null 2>&1; $UA daemon")
        if (!daemon.ok) return fail(log, daemon)
        daemon.out.forEach(log)
    } finally {
        withContext(Dispatchers.IO) { staging.deleteRecursively() }
    }

    return (backendState(context) == BackendState.READY).also {
        log(if (it) "[+] Installed" else "[-] Installed, but the check after it failed")
    }
}

private fun fail(log: (String) -> Unit, r: RootResult): Boolean {
    (r.out + r.err).forEach(log)
    log(if (r.timedOut) "[-] Timed out" else "[-] Failed (exit ${r.code})")
    return false
}
