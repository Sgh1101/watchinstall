package com.sgh1101.watchinstall.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** 설치할 APK 와 그 안에서 읽어 낸 정보. */
data class ApkFile(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val label: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    /** 매니페스트에 android.hardware.type.watch 가 있는지 (= 워치용 앱) */
    val isWatchApp: Boolean,
) {
    companion object {
        private const val WATCH_FEATURE = "android.hardware.type.watch"

        /**
         * 선택한 파일을 앱 캐시로 복사하고 APK 정보를 읽는다.
         * (크기를 정확히 알아야 스트리밍 설치를 할 수 있고, 일부 content:// 는 다시 열 수 없어서 복사해 둔다.)
         */
        suspend fun load(context: Context, uri: Uri): ApkFile = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = queryDisplayName(context, uri) ?: "app.apk"

            val dir = File(context.cacheDir, "incoming").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() } // 이전에 고른 파일 정리
            }
            val target = File(dir, "selected.apk")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "파일을 열 수 없어요." }
                target.outputStream().use { input.copyTo(it) }
            }
            require(target.length() > 0) { "빈 파일이에요." }
            require(isZip(target)) {
                if (name.endsWith(".apks", true) || name.endsWith(".xapk", true) || name.endsWith(".apkm", true)) {
                    "분할 APK 묶음은 아직 지원하지 않아요. 하나로 된 APK 를 골라 주세요."
                } else {
                    "APK 파일이 아니에요."
                }
            }
            if (looksLikeSplitBundle(target)) {
                throw IllegalArgumentException("분할 APK 묶음(.apks)은 아직 지원하지 않아요. 하나로 된 APK 를 골라 주세요.")
            }

            val info = readPackageInfo(context, target)
            val appInfo = info?.applicationInfo?.apply {
                sourceDir = target.absolutePath
                publicSourceDir = target.absolutePath
            }
            val label = appInfo?.let { runCatching { context.packageManager.getApplicationLabel(it).toString() }.getOrNull() }

            ApkFile(
                file = target,
                displayName = name,
                sizeBytes = target.length(),
                label = label,
                packageName = info?.packageName,
                versionName = info?.versionName,
                versionCode = info?.let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
                },
                minSdk = appInfo?.let { if (Build.VERSION.SDK_INT >= 24) it.minSdkVersion else null },
                isWatchApp = info?.reqFeatures?.any { it.name == WATCH_FEATURE } == true,
            )
        }

        @Suppress("DEPRECATION")
        private fun readPackageInfo(context: Context, file: File): PackageInfo? {
            val flags = PackageManager.GET_CONFIGURATIONS
            return if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            }
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

        private fun isZip(file: File): Boolean = file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }

        /** .apks / .xapk 처럼 APK 여러 개를 묶은 zip 인지 확인 */
        private fun looksLikeSplitBundle(file: File): Boolean = runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") == null &&
                    zip.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
