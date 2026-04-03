package top.wsdx233.r2droid.util

import android.content.Context
import android.os.Build
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val DEFAULT_PROOT_ROOTFS_ALIAS = "ubuntu"
private const val DEFAULT_PROOT_ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

data class ProotRootfsOption(
    val alias: String,
    val displayName: String,
    val comment: String,
    val distroType: String,
    val arch: String,
    val tarballUrl: String,
    val sha256: String? = null,
    val tarballStripOpt: Int = 1
) {
    val archiveFileName: String
        get() {
            val rawName = tarballUrl.substringAfterLast('/')
            return URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
        }

    val isRecommended: Boolean
        get() = alias == DEFAULT_PROOT_ROOTFS_ALIAS
}

object ProotRootfsCatalog {
    private const val ASSET_PLUGIN_DIR = "proot-distro/distro-plugins"

    fun defaultOption(): ProotRootfsOption = ProotRootfsOption(
        alias = DEFAULT_PROOT_ROOTFS_ALIAS,
        displayName = "Ubuntu",
        comment = "Default rootfs for automatic setup.",
        distroType = "normal",
        arch = currentArch(),
        tarballUrl = DEFAULT_PROOT_ROOTFS_URL,
        sha256 = null,
        tarballStripOpt = 0
    )

    fun load(context: Context): List<ProotRootfsOption> {
        val arch = currentArch()
        val assetManager = context.assets
        val pluginFiles = runCatching { assetManager.list(ASSET_PLUGIN_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".sh") }
            .sorted()

        if (pluginFiles.isEmpty()) {
            return listOf(defaultOption())
        }

        val entries = pluginFiles.mapNotNull { fileName ->
            runCatching {
                assetManager.open("$ASSET_PLUGIN_DIR/$fileName").bufferedReader().use { reader ->
                    parsePlugin(fileName.removeSuffix(".sh"), arch, reader.readText())
                }
            }.getOrNull()
        }
            .filter { it.distroType.isBlank() || it.distroType == "normal" }
            .distinctBy { it.alias }
            .sortedWith(compareByDescending<ProotRootfsOption> { it.isRecommended }.thenBy { it.displayName.lowercase() })

        return if (entries.isEmpty()) listOf(defaultOption()) else entries
    }

    fun resolve(context: Context, alias: String?): ProotRootfsOption {
        val normalizedAlias = alias?.trim().orEmpty()
        return load(context).firstOrNull { it.alias == normalizedAlias }
            ?: defaultOption()
    }

    private fun parsePlugin(alias: String, arch: String, content: String): ProotRootfsOption? {
        var distroName = alias
        var distroComment = ""
        var distroType = "normal"
        var tarballStripOpt = 1
        val urls = linkedMapOf<String, String>()
        val checksums = linkedMapOf<String, String>()

        val nameRegex = Regex("^DISTRO_NAME=\"(.*)\"$")
        val commentRegex = Regex("^DISTRO_COMMENT=\"(.*)\"$")
        val typeRegex = Regex("^DISTRO_TYPE=\"(.*)\"$")
        val stripRegex = Regex("^TARBALL_STRIP_OPT=(\\d+)$")
        val urlRegex = Regex("^TARBALL_URL\\['([^']+)'\\]=\"(.*)\"$")
        val shaRegex = Regex("^TARBALL_SHA256\\['([^']+)'\\]=\"(.*)\"$")

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                nameRegex.matches(line) -> distroName = nameRegex.find(line)?.groupValues?.get(1) ?: distroName
                commentRegex.matches(line) -> distroComment = commentRegex.find(line)?.groupValues?.get(1).orEmpty()
                typeRegex.matches(line) -> distroType = typeRegex.find(line)?.groupValues?.get(1).orEmpty()
                stripRegex.matches(line) -> tarballStripOpt = stripRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                urlRegex.matches(line) -> {
                    val match = urlRegex.find(line) ?: return@forEach
                    urls[match.groupValues[1]] = match.groupValues[2]
                }
                shaRegex.matches(line) -> {
                    val match = shaRegex.find(line) ?: return@forEach
                    checksums[match.groupValues[1]] = match.groupValues[2]
                }
            }
        }

        val url = urls[arch] ?: return null
        return ProotRootfsOption(
            alias = File(alias).nameWithoutExtension,
            displayName = distroName,
            comment = distroComment,
            distroType = distroType,
            arch = arch,
            tarballUrl = url,
            sha256 = checksums[arch],
            tarballStripOpt = tarballStripOpt
        )
    }

    fun currentArch(): String {
        return Build.SUPPORTED_ABIS
            .mapNotNull { abi ->
                when (abi) {
                    "arm64-v8a" -> "aarch64"
                    "armeabi-v7a", "armeabi" -> "arm"
                    "x86_64" -> "x86_64"
                    "x86" -> "i686"
                    "riscv64" -> "riscv64"
                    else -> null
                }
            }
            .firstOrNull()
            ?: "aarch64"
    }
}
