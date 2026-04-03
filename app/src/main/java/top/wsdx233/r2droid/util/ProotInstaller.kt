package top.wsdx233.r2droid.util

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

private const val DEFAULT_HOST_R2RC = "e scr.interactive = false\ne r2ghidra.sleighhome = %s"
private const val DEFAULT_PROOT_R2RC = "e scr.interactive = false\ne r2ghidra.sleighhome = /root/.local/share/radare2/plugins/r2ghidra_sleigh"

data class ProotInstallState(
    val status: Status = Status.IDLE,
    val progress: Float = 0f,
    val message: String = "",
    val logs: List<String> = emptyList()
) {
    enum class Status {
        IDLE,
        PREPARING,
        DOWNLOADING,
        EXTRACTING,
        CONFIGURING,
        INSTALLING_PACKAGES,
        INSTALLING_PLUGINS,
        DONE,
        ERROR
    }

    val isWorking: Boolean
        get() = status in setOf(
            Status.PREPARING,
            Status.DOWNLOADING,
            Status.EXTRACTING,
            Status.CONFIGURING,
            Status.INSTALLING_PACKAGES,
            Status.INSTALLING_PLUGINS
        )
}

object ProotInstaller {
    private const val TAG = "ProotInstaller"
    private const val PROOT_ASSET_NAME = "proot"
    private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
    private const val ROOTFS_ARCHIVE_NAME = "ubuntu-base-24.04.3-base-arm64.tar.gz"
    private const val READY_MARKER_NAME = ".setup-complete"
    private const val MAX_LOG_LINES = 160

    private val installMutex = Mutex()

    private val _state = MutableStateFlow(ProotInstallState())
    val state = _state.asStateFlow()

    fun getRuntimeDir(context: Context): File = File(context.filesDir, "proot")

    fun getBinDir(context: Context): File = File(getRuntimeDir(context), "bin")

    fun getProotBinary(context: Context): File = File(getBinDir(context), "proot")

    fun getRootfsDir(context: Context): File = File(getRuntimeDir(context), "ubuntu")

    fun getHostTmpDir(context: Context): File = File(getRuntimeDir(context), "tmp")

    fun getReadyMarker(context: Context): File = File(getRuntimeDir(context), READY_MARKER_NAME)

    fun getR2rcFile(context: Context): File = File(getRootfsDir(context), "root/.radare2rc")

    fun isEnvironmentReady(context: Context): Boolean {
        return getProotBinary(context).exists() && getReadyMarker(context).exists() && getRootfsDir(context).isDirectory
    }

    fun resetState() {
        _state.value = ProotInstallState()
    }

    fun ensureRuntimeBinary(context: Context) {
        val target = getProotBinary(context)
        val runtimeDir = getRuntimeDir(context)
        val assets = context.assets

        runtimeDir.mkdirs()
        target.parentFile?.mkdirs()

        val assetSize = runCatching { assets.openFd(PROOT_ASSET_NAME).length }.getOrNull()
        val needsCopy = !target.exists() || assetSize == null || target.length() != assetSize

        if (!needsCopy) {
            runCatching { Os.chmod(target.absolutePath, 493) }
            return
        }

        assets.open(PROOT_ASSET_NAME).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        Os.chmod(target.absolutePath, 493)
    }

    suspend fun install(context: Context, forceReinstall: Boolean = false): Result<Unit> {
        val appContext = context.applicationContext
        return installMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    _state.value = ProotInstallState()
                    if (!forceReinstall && isEnvironmentReady(appContext)) {
                        _state.value = ProotInstallState(
                            status = ProotInstallState.Status.DONE,
                            progress = 1f,
                            message = "Proot environment is ready."
                        )
                        return@runCatching
                    }

                    ensureRuntimeBinary(appContext)
                    prepareRuntime(appContext, forceReinstall)
                    downloadRootfsIfNeeded(appContext)
                    extractRootfsIfNeeded(appContext)
                    configureRootfs(appContext)
                    installPackages(appContext)
                    installPlugins(appContext)
                    syncR2rc(appContext)
                    getReadyMarker(appContext).apply {
                        parentFile?.mkdirs()
                        writeText("url=$ROOTFS_URL\n")
                    }
                    _state.value = _state.value.copy(
                        status = ProotInstallState.Status.DONE,
                        progress = 1f,
                        message = "Proot environment is ready."
                    )
                    appendLog("Environment setup completed.")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to install proot environment", error)
                    appendLog("ERROR: ${error.message ?: "unknown error"}")
                    _state.value = _state.value.copy(
                        status = ProotInstallState.Status.ERROR,
                        message = error.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    suspend fun installManual(context: Context, forceReinstall: Boolean = false): Result<Unit> {
        val appContext = context.applicationContext
        return installMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    _state.value = ProotInstallState()
                    if (!forceReinstall && isEnvironmentReady(appContext)) {
                        _state.value = ProotInstallState(
                            status = ProotInstallState.Status.DONE,
                            progress = 1f,
                            message = "Proot environment is ready."
                        )
                        return@runCatching
                    }

                    ensureRuntimeBinary(appContext)
                    prepareRuntime(appContext, forceReinstall)
                    downloadRootfsIfNeeded(appContext)
                    extractRootfsIfNeeded(appContext)
                    configureRootfs(appContext)
                    getReadyMarker(appContext).apply {
                        parentFile?.mkdirs()
                        writeText("url=$ROOTFS_URL\nmode=manual\n")
                    }
                    _state.value = _state.value.copy(
                        status = ProotInstallState.Status.DONE,
                        progress = 1f,
                        message = "Proot Ubuntu environment is ready. Please configure r2 and plugins manually via the proot terminal."
                    )
                    appendLog("Manual mode: Ubuntu proot setup completed. Use the proot terminal to install r2 and plugins.")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to install proot environment (manual)", error)
                    appendLog("ERROR: ${error.message ?: "unknown error"}")
                    _state.value = _state.value.copy(
                        status = ProotInstallState.Status.ERROR,
                        message = error.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private fun prepareRuntime(context: Context, forceReinstall: Boolean) {
        updateState(ProotInstallState.Status.PREPARING, 0.05f, "Preparing proot runtime...")
        appendLog("Ensuring proot binary is installed.")
        getRuntimeDir(context).mkdirs()
        getRootfsDir(context).mkdirs()
        getHostTmpDir(context).mkdirs()
        runCatching { Os.chmod(getHostTmpDir(context).absolutePath, 511) }
        getReadyMarker(context).delete()

        if (forceReinstall) {
            appendLog("Force reinstall requested, clearing existing rootfs.")
            getRootfsDir(context).deleteRecursively()
            getRootfsDir(context).mkdirs()
        }
    }

    private fun downloadRootfsIfNeeded(context: Context) {
        val archive = getArchiveFile(context)
        if (archive.exists() && archive.length() > 0L) {
            appendLog("Using cached rootfs archive: ${archive.absolutePath}")
            return
        }

        updateState(ProotInstallState.Status.DOWNLOADING, 0.1f, "Downloading Ubuntu base rootfs...")
        appendLog("Downloading $ROOTFS_URL")

        val conn = java.net.URL(ROOTFS_URL).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.connect()

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("Failed to download rootfs: HTTP ${conn.responseCode}")
        }

        val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L
        var lastReported = 0L

        archive.parentFile?.mkdirs()
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(64 * 1024)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    if (downloaded - lastReported >= 256 * 1024 || (totalBytes > 0 && downloaded == totalBytes)) {
                        lastReported = downloaded
                        if (totalBytes > 0) {
                            val stageProgress = 0.1f + (downloaded.toFloat() / totalBytes.toFloat()) * 0.25f
                            updateState(
                                ProotInstallState.Status.DOWNLOADING,
                                stageProgress.coerceAtMost(0.35f),
                                "Downloading Ubuntu base rootfs..."
                            )
                        }
                    }
                }
            }
        }
        conn.disconnect()
        appendLog("Download finished (${archive.length()} bytes).")
    }

    private fun extractRootfsIfNeeded(context: Context) {
        val rootfsDir = getRootfsDir(context)
        val readyMarker = getReadyMarker(context)
        if (readyMarker.exists() && rootfsDir.resolve("bin/bash").exists()) {
            appendLog("Existing rootfs looks ready, skipping extraction.")
            return
        }

        val archive = getArchiveFile(context)
        if (!archive.exists()) {
            throw IllegalStateException("Rootfs archive is missing.")
        }

        updateState(ProotInstallState.Status.EXTRACTING, 0.38f, "Extracting Ubuntu base rootfs...")
        appendLog("Extracting rootfs to ${rootfsDir.absolutePath}")

        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        val totalBytes = archive.length().takeIf { it > 0 } ?: -1L
        var bytesReadTotal = 0L
        var lastReported = 0L

        archive.inputStream().buffered().use { fileInput ->
            val countingInput = object : InputStream() {
                override fun read(): Int {
                    val value = fileInput.read()
                    if (value != -1) updateProgress(1)
                    return value
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = fileInput.read(b, off, len)
                    if (read > 0) updateProgress(read.toLong())
                    return read
                }

                private fun updateProgress(bytesRead: Long) {
                    bytesReadTotal += bytesRead
                    if (bytesReadTotal - lastReported >= 256 * 1024 || (totalBytes > 0 && bytesReadTotal >= totalBytes)) {
                        lastReported = bytesReadTotal
                        if (totalBytes > 0) {
                            val stageProgress = 0.38f + (bytesReadTotal.toFloat() / totalBytes.toFloat()) * 0.22f
                            updateState(
                                ProotInstallState.Status.EXTRACTING,
                                stageProgress.coerceAtMost(0.6f),
                                "Extracting Ubuntu base rootfs..."
                            )
                        }
                    }
                }
            }

            TarArchiveInputStream(GzipCompressorInputStream(countingInput)).use { tarInput ->
                var entry: TarArchiveEntry?
                while (tarInput.nextEntry.also { entry = it } != null) {
                    val currentEntry = entry ?: continue
                    val outputFile = File(rootfsDir, currentEntry.name)
                    if (!outputFile.canonicalPath.startsWith(rootfsDir.canonicalPath)) {
                        throw SecurityException("Invalid archive entry: ${currentEntry.name}")
                    }
                    when {
                        currentEntry.isDirectory -> outputFile.mkdirs()
                        currentEntry.isSymbolicLink -> handleSymlink(outputFile, currentEntry.linkName)
                        currentEntry.isLink -> handleHardLink(rootfsDir, outputFile, currentEntry.linkName)
                        currentEntry.isFile -> handleRegularFile(tarInput, outputFile)
                        else -> appendLog("Skipping unsupported tar entry: ${currentEntry.name}")
                    }
                    if (!currentEntry.isSymbolicLink && !currentEntry.isLink) {
                        setFilePermissions(outputFile, currentEntry.mode)
                    }
                }
            }
        }
        appendLog("Extraction finished.")
    }

    private fun configureRootfs(context: Context) {
        updateState(ProotInstallState.Status.CONFIGURING, 0.64f, "Configuring Ubuntu environment...")
        val rootfsDir = getRootfsDir(context)
        val dnsServers = queryDnsServers().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText(dnsServers.joinToString(separator = "\n") { "nameserver $it" } + "\n")

        // Disable apt sandbox to avoid setresuid failures under proot fake-root
        val aptConfDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "99proot-nosandbox").writeText("APT::Sandbox::User \"root\";\n")

        // Force dpkg to skip fsync/sync calls that fail under proot
        val dpkgConfDir = File(rootfsDir, "etc/dpkg/dpkg.cfg.d")
        dpkgConfDir.mkdirs()
        File(dpkgConfDir, "force-unsafe-io").writeText("force-unsafe-io\n")

        val hostsFile = File(rootfsDir, "etc/hosts")
        if (!hostsFile.exists()) {
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }

        // Some Android devices do not expose a host /tmp. Provide both container and host temp dirs.
        val containerTmpDir = File(rootfsDir, "tmp")
        containerTmpDir.mkdirs()
        runCatching { Os.chmod(containerTmpDir.absolutePath, 511) }
        val containerVarTmpDir = File(rootfsDir, "var/tmp")
        containerVarTmpDir.mkdirs()
        runCatching { Os.chmod(containerVarTmpDir.absolutePath, 511) }
        val hostTmpDir = getHostTmpDir(context)
        hostTmpDir.mkdirs()
        runCatching { Os.chmod(hostTmpDir.absolutePath, 511) }

        // Provide a fake /proc/sys/crypto/fips_enabled so libgcrypt doesn't crash
        val fakeProcCrypto = File(rootfsDir, "proc/sys/crypto")
        fakeProcCrypto.mkdirs()
        File(fakeProcCrypto, "fips_enabled").writeText("0\n")

        appendLog("Configured DNS: ${dnsServers.joinToString(", ")}")
    }

    private fun installPackages(context: Context) {
        val setupCommands = listOf(
            Triple(0.7f, "Updating apt sources...", """
                set -e
                if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
                    sed -i 's/^Components: .*/Components: main restricted universe multiverse/' /etc/apt/sources.list.d/ubuntu.sources
                fi
                apt-get update
            """.trimIndent()),
            Triple(0.78f, "Installing build toolchain...", """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                apt-get install -y --no-install-recommends \
                    ca-certificates \
                    cmake \
                    curl \
                    file \
                    git \
                    make \
                    meson \
                    ninja-build \
                    patch \
                    pkg-config \
                    python3 \
                    python3-pip \
                    unzip \
                    wget \
                    xz-utils \
                    build-essential \
                    libssl-dev \
                    libzip-dev \
                    openjdk-21-jre-headless
            """.trimIndent()),
            Triple(0.86f, "Installing radare2 from source...", """
                set -e
                export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
                export MAKE_JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
                mkdir -p /opt/src
                if [ ! -d /opt/src/radare2/.git ]; then
                    git clone --depth 1 https://github.com/radareorg/radare2 /opt/src/radare2
                else
                    cd /opt/src/radare2
                    git fetch --depth 1 origin
                    git reset --hard origin/master
                fi
                cd /opt/src/radare2
                rm -rf build
                sys/install.sh
                hash -r
                command -v r2 >/dev/null
                command -v r2pm >/dev/null
                r2 -v
                r2pm -h >/dev/null
                ldconfig || true
                ls -l /usr/local/lib/pkgconfig/r_core.pc || true
            """.trimIndent())
        )

        setupCommands.forEach { (progress, message, command) ->
            updateState(ProotInstallState.Status.INSTALLING_PACKAGES, progress, message)
            runProotCommand(context, command)
        }
    }

    private fun installPlugins(context: Context) {
        val pluginCommands = listOf(
            Triple(0.9f, "Installing r2dec with r2pm...", """
                set -e
                export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
                export LD_LIBRARY_PATH=/usr/local/lib:/usr/lib:/usr/lib/aarch64-linux-gnu:${'$'}{LD_LIBRARY_PATH:-}
                export LIBRARY_PATH=/usr/local/lib:/usr/lib:/usr/lib/aarch64-linux-gnu:${'$'}{LIBRARY_PATH:-}
                export PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:/usr/lib/pkgconfig:/usr/lib/aarch64-linux-gnu/pkgconfig:${'$'}{PKG_CONFIG_PATH:-}
                R2PM_BIN=/usr/local/bin/r2pm
                [ -x "${'$'}R2PM_BIN" ] || R2PM_BIN="$(command -v r2pm)"
                bash "${'$'}R2PM_BIN" init || true
                bash "${'$'}R2PM_BIN" update || true
                bash "${'$'}R2PM_BIN" -ci r2dec
            """.trimIndent()),
            Triple(0.97f, "Installing r2ghidra with r2pm...", """
                set -e
                export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
                export LD_LIBRARY_PATH=/usr/local/lib:/usr/lib:/usr/lib/aarch64-linux-gnu:${'$'}{LD_LIBRARY_PATH:-}
                export LIBRARY_PATH=/usr/local/lib:/usr/lib:/usr/lib/aarch64-linux-gnu:${'$'}{LIBRARY_PATH:-}
                export PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:/usr/lib/pkgconfig:/usr/lib/aarch64-linux-gnu/pkgconfig:${'$'}{PKG_CONFIG_PATH:-}
                R2PM_BIN=/usr/local/bin/r2pm
                [ -x "${'$'}R2PM_BIN" ] || R2PM_BIN="$(command -v r2pm)"
                bash "${'$'}R2PM_BIN" init || true
                bash "${'$'}R2PM_BIN" update || true
                bash "${'$'}R2PM_BIN" -ci r2ghidra
            """.trimIndent())
        )

        pluginCommands.forEach { (progress, message, command) ->
            updateState(ProotInstallState.Status.INSTALLING_PLUGINS, progress, message)
            runProotCommand(context, command)
        }
    }

    private fun syncR2rc(context: Context) {
        val target = getR2rcFile(context)
        target.parentFile?.mkdirs()

        val current = target.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (current.isNotBlank()) {
            appendLog("Keeping existing proot .radare2rc")
            return
        }

        val hostR2rc = File(context.filesDir, "radare2/bin/.radare2rc")
        val content = when {
            hostR2rc.exists() && hostR2rc.readText().isNotBlank() -> {
                val hostSleighPath = String.format(
                    DEFAULT_HOST_R2RC,
                    File(context.filesDir, "r2work/radare2/plugins/r2ghidra_sleigh").absolutePath
                ).substringAfter('\n')
                hostR2rc.readText().replace(hostSleighPath, DEFAULT_PROOT_R2RC.substringAfter('\n'))
            }
            else -> DEFAULT_PROOT_R2RC
        }
        target.writeText(content)
        appendLog("Wrote default proot .radare2rc")
    }

    private fun runProotCommand(context: Context, script: String) {
        appendLog("$ ${script.lineSequence().firstOrNull() ?: "command"}")

        // Prepend cd /root to avoid getcwd errors inside proot
        val wrappedScript = "cd /root 2>/dev/null; $script"

        val spec = R2Runtime.buildProotShellSpec(
            context = context,
            shellCommand = wrappedScript,
            term = "dumb",
            extraBindPaths = emptySet()
        )
        val processBuilder = ProcessBuilder(spec.command)
        processBuilder.directory(spec.workingDirectory)
        processBuilder.environment().putAll(spec.environment)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    appendLog(line)
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Command failed with exit code $exitCode")
        }
    }

    private fun queryDnsServers(): List<String> {
        val keys = listOf(
            "net.dns1",
            "net.dns2",
            "net.dns3",
            "net.dns4",
            "dhcp.wlan0.dns1",
            "dhcp.wlan0.dns2",
            "dhcp.rmnet0.dns1",
            "dhcp.rmnet0.dns2"
        )

        return keys.mapNotNull { key ->
            val value = runCatching {
                ProcessBuilder("/system/bin/getprop", key)
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            }.getOrNull()
            value?.takeIf { it.matches(Regex("^[0-9a-fA-F:.]+$")) }
        }.distinct()
    }

    private fun updateState(status: ProotInstallState.Status, progress: Float, message: String) {
        _state.value = _state.value.copy(status = status, progress = progress, message = message)
    }

    private fun appendLog(line: String) {
        val logs = (_state.value.logs + line).takeLast(MAX_LOG_LINES)
        _state.value = _state.value.copy(logs = logs)
    }

    private fun getArchiveFile(context: Context): File = File(context.cacheDir, "proot/$ROOTFS_ARCHIVE_NAME")

    private fun handleSymlink(linkFile: File, targetPath: String) {
        linkFile.parentFile?.mkdirs()
        runCatching { linkFile.delete() }
        Os.symlink(targetPath, linkFile.absolutePath)
    }

    private fun handleHardLink(rootfsDir: File, linkFile: File, targetPath: String) {
        linkFile.parentFile?.mkdirs()
        runCatching { linkFile.delete() }

        val normalizedTarget = targetPath.removePrefix("/")
        val targetFile = File(rootfsDir, normalizedTarget)
        val linkParent = linkFile.parentFile ?: rootfsDir
        val symlinkTarget = runCatching {
            if (targetPath.startsWith("/")) {
                "/$normalizedTarget"
            } else {
                targetFile.relativeTo(linkParent).path
            }
        }.getOrDefault(targetPath)

        Os.symlink(symlinkTarget, linkFile.absolutePath)
    }

    private fun handleRegularFile(tarInput: TarArchiveInputStream, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            val buffer = ByteArray(8192)
            var len: Int
            while (tarInput.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
            }
        }
    }

    private fun setFilePermissions(file: File, mode: Int) {
        var permissions = mode and 0b111111111
        if (permissions > 0) {
            // Force owner read+write (and execute for dirs) so dpkg can
            // modify/delete files under proot's fake-root where the host
            // kernel still enforces real UID permission checks.
            permissions = if (file.isDirectory) {
                permissions or 448  // 0700
            } else {
                permissions or 384  // 0600
            }
            runCatching { Os.chmod(file.absolutePath, permissions) }
        }
    }
}
