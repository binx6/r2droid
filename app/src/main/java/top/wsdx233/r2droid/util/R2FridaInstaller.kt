package top.wsdx233.r2droid.util

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wsdx233.r2droid.core.data.prefs.SettingsManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class R2FridaInstallState(
    val status: Status = Status.IDLE,
    val progress: Float = 0f,
    val message: String = "",
    val version: String = ""
) {
    enum class Status { IDLE, FETCHING, DOWNLOADING, EXTRACTING, INSTALLING, DONE, ERROR }
}

object R2FridaInstaller {
    private const val TAG = "R2FridaInstaller"
    private const val GITHUB_API = "https://api.github.com/repos/wsdx233/r2frida-android-arm64-build/releases/latest"
    private const val GITEE_API = "https://gitee.com/api/v5/repos/wsdx233/r2frida-android-arm64-build/releases/latest"
    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(R2FridaInstallState())
    val state = _state.asStateFlow()

    fun getPluginsDir(context: Context): File =
        File(context.filesDir, "r2work/radare2/plugins")

    fun isInstalled(context: Context): Boolean {
        return if (SettingsManager.useProotMode) {
            ProotInstaller.isEnvironmentReady(context) && ProotInstaller.isR2FridaInstalled(context)
        } else {
            getPluginsDir(context).listFiles()?.any { it.name.startsWith("io_frida") && it.name.endsWith(".so") } == true
        }
    }

    fun resetState() {
        _state.value = R2FridaInstallState()
    }

    suspend fun install(context: Context, useChinaSource: Boolean) = withContext(Dispatchers.IO) {
        try {
            if (SettingsManager.useProotMode) {
                installInProot(context)
            } else {
                installHostPlugin(context, useChinaSource)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            _state.value = R2FridaInstallState(
                R2FridaInstallState.Status.ERROR,
                message = e.message ?: "Unknown error"
            )
        }
    }

    private fun installInProot(context: Context) {
        require(ProotInstaller.isEnvironmentReady(context)) {
            "Finish proot setup in Settings first."
        }

        _state.value = R2FridaInstallState(
            status = R2FridaInstallState.Status.INSTALLING,
            progress = 0.15f,
            message = "Installing r2frida in proot...",
            version = "proot"
        )

        ProotInstaller.runProotCommand(context, buildProotInstallScript()) { line ->
            when {
                "apt-get update" in line || "Resolving" in line -> {
                    _state.value = _state.value.copy(progress = 0.3f, message = "Preparing proot packages...")
                }
                "apt-get install" in line || "Selecting previously unselected" in line -> {
                    _state.value = _state.value.copy(progress = 0.5f, message = "Installing dependencies in proot...")
                }
                "r2pm" in line || "git clone" in line || "meson" in line || "ninja" in line -> {
                    _state.value = _state.value.copy(progress = 0.8f, message = "Installing r2frida in proot...")
                }
            }
        }

        if (!ProotInstaller.isR2FridaInstalled(context)) {
            throw IllegalStateException("r2frida installation finished but plugin was not found in proot.")
        }

        _state.value = R2FridaInstallState(
            status = R2FridaInstallState.Status.DONE,
            progress = 1f,
            message = "r2frida is ready in proot.",
            version = "proot"
        )
    }

    private fun buildProotInstallScript(): String = """
        set -e
        export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
        export LD_LIBRARY_PATH=/usr/local/lib:/usr/lib:/usr/lib/aarch64-linux-gnu:${'$'}{LD_LIBRARY_PATH:-}
        export LIBRARY_PATH=/usr/local/lib:/usr/lib:/usr/lib/aarch64-linux-gnu:${'$'}{LIBRARY_PATH:-}
        export PKG_CONFIG_PATH=/usr/local/lib/pkgconfig:/usr/lib/pkgconfig:/usr/lib/aarch64-linux-gnu/pkgconfig:${'$'}{PKG_CONFIG_PATH:-}
        export DEBIAN_FRONTEND=noninteractive
        if command -v apt-get >/dev/null 2>&1; then
            if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
                sed -i 's/^Components: .*/Components: main restricted universe multiverse/' /etc/apt/sources.list.d/ubuntu.sources || true
            fi
            apt-get update
            apt-get install -y --no-install-recommends \
                git \
                build-essential \
                pkgconf \
                pkg-config \
                wget \
                ninja-build \
                meson
        fi
        R2PM_BIN="$(command -v r2pm || true)"
        [ -n "${'$'}R2PM_BIN" ] || R2PM_BIN=/usr/local/bin/r2pm
        [ -x "${'$'}R2PM_BIN" ] || { echo "r2pm not found"; exit 127; }
        "${'$'}R2PM_BIN" -U
        hash -r
        "${'$'}R2PM_BIN" -ci r2frida
    """.trimIndent()

    private fun installHostPlugin(context: Context, useChinaSource: Boolean) {
        _state.value = R2FridaInstallState(R2FridaInstallState.Status.FETCHING, message = "Fetching release info...")

        val apiUrl = if (useChinaSource) GITEE_API else GITHUB_API
        val json = fetchJson(apiUrl)
        val version = json["tag_name"]?.jsonPrimitive?.content ?: "unknown"
        val assets = json["assets"]?.jsonArray ?: throw Exception("No assets found")

        val soAsset = assets.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content?.endsWith(".so") == true
        }
        val zipAsset = assets.firstOrNull {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            name.endsWith(".zip") && !name.startsWith("v")
        }

        val pluginsDir = getPluginsDir(context)
        pluginsDir.mkdirs()

        if (soAsset != null) {
            val downloadUrl = soAsset.jsonObject["browser_download_url"]!!.jsonPrimitive.content
            val fileName = soAsset.jsonObject["name"]!!.jsonPrimitive.content
            val targetFile = File(pluginsDir, fileName)

            _state.value = R2FridaInstallState(R2FridaInstallState.Status.DOWNLOADING, version = version)
            downloadFile(downloadUrl, targetFile)
            Os.chmod(targetFile.absolutePath, 493)
        } else if (zipAsset != null) {
            val downloadUrl = zipAsset.jsonObject["browser_download_url"]!!.jsonPrimitive.content
            val tempZip = File(context.cacheDir, "r2frida_temp.zip")

            _state.value = R2FridaInstallState(R2FridaInstallState.Status.DOWNLOADING, version = version)
            downloadFile(downloadUrl, tempZip)

            _state.value = R2FridaInstallState(R2FridaInstallState.Status.EXTRACTING, progress = 0.9f, version = version)
            extractSoFromZip(tempZip, pluginsDir)
            tempZip.delete()
        } else {
            throw Exception("No suitable asset found")
        }

        _state.value = R2FridaInstallState(R2FridaInstallState.Status.DONE, progress = 1f, version = version)
    }

    private fun fetchJson(apiUrl: String): JsonObject {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val text = conn.inputStream.bufferedReader().readText()
            jsonParser.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.instanceFollowRedirects = true
        val totalBytes = conn.contentLength.toLong()
        var downloaded = 0L

        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    downloaded += len
                    if (totalBytes > 0) {
                        _state.value = _state.value.copy(
                            progress = (downloaded.toFloat() / totalBytes).coerceAtMost(0.9f)
                        )
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun extractSoFromZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".so")) {
                    val outFile = File(targetDir, File(entry.name).name)
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    Os.chmod(outFile.absolutePath, 493)
                }
                entry = zis.nextEntry
            }
        }
    }
}
