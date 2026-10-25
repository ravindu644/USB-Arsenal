package com.usbarsenal.app.backend

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// Ported from Droidspaces' BinaryInstaller, ModuleInstaller, DeviceArch and DroidspacesChecker
const val MODULE = "/data/adb/modules/usb-arsenal"

// The PATH link Droidspaces' SymlinkInstaller makes: the binary stays in the workspace, and this
// links to it only while the user has asked for it
private const val MODULE_LINK = "$MODULE/system/bin/usb-arsenal"
private val MODULE_FILES = listOf("module.prop", "uninstall.sh", "sepolicy.rule")

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

// [installed] is the version the installed binary reports, when it runs
data class Backend(val state: BackendState, val installed: String?)

suspend fun backendState(context: Context): Backend {
    val r = Root.read(
        """
        echo root
        test -x $UA && echo bin
        test -x $QUACKY && echo quacky
        test -f $MODULE/module.prop && echo module
        v=$($UA version 2>/dev/null) && echo "${'$'}v" | head -n 1 | sed 's/^/runs /'
        md5sum $UA $QUACKY 2>/dev/null
        """.trimIndent(),
    )
    // "runs usb-arsenal 1.2.0"
    val installed = r.out.firstOrNull { it.startsWith("runs ") }?.substringAfterLast(' ')?.takeIf { it.isNotEmpty() }
    // md5sum prints "<hash>  <path>"; tie each hash to its binary by the path at the line's end
    fun md5Of(path: String) = r.out.firstNotNullOfOrNull {
        Regex("^([0-9a-f]{32}) +(.+)$").find(it)?.takeIf { m -> m.groupValues[2] == path }?.groupValues?.get(1)
    }
    // A shell that never said `root` never ran: su denied it (Magisk exits 13 with nothing said)
    // or is missing, so nothing was checked. No binary bundled for this ABI is nothing to update to
    val uaAsset = assetMd5(context, "usb-arsenal")
    val quackyAsset = assetMd5(context, "quacky")
    val state = when {
        "root" !in r.out -> BackendState.NO_ROOT
        "bin" !in r.out -> BackendState.NOT_INSTALLED
        "module" !in r.out -> BackendState.MODULE_MISSING
        installed == null -> BackendState.CORRUPTED
        // quacky is part of the backend; missing it is a broken install, fixed by a reinstall
        "quacky" !in r.out -> BackendState.CORRUPTED
        uaAsset != null && md5Of(UA) != uaAsset -> BackendState.UPDATE_AVAILABLE
        quackyAsset != null && md5Of(QUACKY) != quackyAsset -> BackendState.UPDATE_AVAILABLE
        else -> BackendState.READY
    }
    return Backend(state, installed)
}

private suspend fun assetMd5(context: Context, name: String): String? {
    val arch = deviceArch() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("binaries/$name-$arch").use { MessageDigest.getInstance("MD5").digest(it.readBytes()).toHexString() }
        }.getOrNull()
    }
}

/*
 * Droidspaces' install: the binary into the workspace, the module's three files beside it
 * (never customize.sh, which only the zip runs), each step reporting what it really printed.
 * The module zip's customize.sh lands the same files.
 */
suspend fun installBackend(context: Context, log: (String) -> Unit): Boolean =
    withContext(NonCancellable) { installing.withLock { install(context, log) } }

// One install at a time: a second one would rewrite the staged binary while the first copies it
private val installing = Mutex()

private suspend fun install(context: Context, log: (String) -> Unit): Boolean {
    val arch = deviceArch() ?: run {
        log("[-] No usb-arsenal binary for ${Build.SUPPORTED_ABIS.firstOrNull()}")
        return false
    }
    log("[+] Device architecture: $arch")

    // The manager loads sepolicy.rule at boot. The rule already in the module is in the policy
    // now; a new or changed one waits for a reboot. It is never patched into the live policy,
    // which root detectors watch for. Asked before anything is copied, as Droidspaces does
    val oldRule = Root.read("cat $MODULE/sepolicy.rule").takeIf { it.ok }?.out
    val newRule = withContext(Dispatchers.IO) { context.assets.open("boot-module/sepolicy.rule").bufferedReader().readLines() }
    val ruleIsNew = oldRule != newRule.dropLastWhile(String::isEmpty)

    val staging = File(context.cacheDir, "backend")
    try {
        stageBinary(context, staging)
        withContext(Dispatchers.IO) {
            context.assets.open("binaries/quacky-$arch").use { i -> File(staging, "quacky").outputStream().use(i::copyTo) }
            for (f in MODULE_FILES) {
                context.assets.open("boot-module/$f").use { i -> File(staging, f).outputStream().use(i::copyTo) }
            }
        }
        val s = shellQuote(staging.absolutePath)

        log("[+] Installing the binary in $WORKSPACE/bin")
        // Copied beside the target and renamed over it, so a hard reboot leaves the old binary or
        // the new one, and a copy still running the old one is never written to (Droidspaces'
        // installFileDurably). It also replaces the link an older install left here
        val binary = Root.change(
            """
            mkdir -p $WORKSPACE/bin &&
            { cp $s/usb-arsenal $UA.new && chmod 755 $UA.new && sync && mv -f $UA.new $UA && sync; } ||
            { rm -f $UA.new; false; }
            """.trimIndent(),
        )
        if (!step(log, binary)) return false

        log("[+] Installing quacky in $WORKSPACE/bin")
        val quacky = Root.change(
            """
            { cp $s/quacky $QUACKY.new && chmod 755 $QUACKY.new && sync && mv -f $QUACKY.new $QUACKY && sync; } ||
            { rm -f $QUACKY.new; false; }
            """.trimIndent(),
        )
        if (!step(log, quacky)) return false

        log("[+] Installing the boot module in $MODULE")
        // A remove or disable mark from the manager, or a zip still pending in modules_update,
        // would undo this at the next boot. An older install kept the binary itself in
        // system/bin; that goes, and a PATH link the user asked for stays
        val module = Root.change(
            """
            rm -rf $MODULE/remove $MODULE/disable /data/adb/modules_update/usb-arsenal &&
            { [ -L $MODULE_LINK ] || rm -rf $MODULE/system; } &&
            mkdir -p $MODULE &&
            for f in ${MODULE_FILES.joinToString(" ")}; do cp $s/${'$'}f $MODULE/${'$'}f || exit 1; done &&
            chmod 755 $MODULE/uninstall.sh &&
            chmod 644 $MODULE/module.prop $MODULE/sepolicy.rule
            """.trimIndent(),
        )
        if (!step(log, module)) return false
    } finally {
        // Check's copy of the binary too: once installed, nothing runs it again
        withContext(Dispatchers.IO) {
            staging.deleteRecursively()
            File(context.cacheDir, "check").deleteRecursively()
        }
    }

    val ok = backendState(context).state == BackendState.READY
    log(if (ok) "[+] Installed" else "[-] Installed, but the check after it failed")
    if (ok && ruleIsNew) log("[!] Reboot to load the SELinux rule")
    return ok
}

// A step prints what it really printed, then, when it failed, why it stopped
private fun step(log: (String) -> Unit, r: RootResult): Boolean {
    (r.out + r.err).forEach(log)
    if (!r.ok) log(if (r.timedOut) "[-] Timed out" else "[-] Failed (exit ${r.code})")
    return r.ok
}

/*
 * The bundled binary for this device, copied into a fresh [dir] and made executable, so setup
 * can run it before anything is installed. Null when we ship none for this ABI.
 */
suspend fun stageBinary(context: Context, dir: File = File(context.cacheDir, "check")): File? {
    val arch = deviceArch() ?: return null
    return withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "usb-arsenal").also { bin ->
            context.assets.open("binaries/usb-arsenal-$arch").use { i -> bin.outputStream().use(i::copyTo) }
            bin.setExecutable(true)
        }
    }
}

// Droidspaces' SymlinkInstaller: usb-arsenal on PATH is the module's system/bin link to the workspace binary
suspend fun pathLinked(): Boolean? = Root.read("test -L $MODULE_LINK && echo 1 || echo 0").takeIf { it.ok }?.out?.firstOrNull()?.let { it == "1" }

// Only into an installed module, to the binary there; off takes system/ away whole, as the installer does
suspend fun setPathLinked(on: Boolean) = Root.change(
    if (on) {
        "test -f $MODULE/module.prop -a -x $UA || { echo '[-] Install the backend first' >&2; exit 1; }; " +
            "mkdir -p $MODULE/system/bin && ln -sfn $UA $MODULE_LINK"
    } else {
        "rm -rf $MODULE/system"
    },
)
