package com.sgh1101.watchinstall.install

import com.sgh1101.watchinstall.adb.WatchAdb

/** 연결된 시계의 기본 정보 */
data class WatchInfo(
    val model: String,
    val androidVersion: String,
    val sdk: Int?,
)

/** 연결된 시계에 대해 정보 조회와 APK 설치를 한다. */
class WatchInstaller(private val adb: WatchAdb) {

    suspend fun readWatchInfo(): WatchInfo {
        val out = adb.exec(
            "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk",
        ).output.lines().map { it.trim() }
        return WatchInfo(
            model = out.getOrNull(0).orEmpty().ifEmpty { "알 수 없는 기기" },
            androidVersion = out.getOrNull(1).orEmpty(),
            sdk = out.getOrNull(2)?.toIntOrNull(),
        )
    }

    /**
     * `adb install` 과 같은 방식(스트리밍 설치)으로 APK 를 시계에 설치한다.
     *  -r : 이미 있으면 교체
     *  -t : testOnly APK 도 허용 (Android Studio 의 Run 으로 만든 APK)
     *  -S : stdin 으로 보낼 크기
     */
    suspend fun install(apk: ApkFile, onProgress: (Float) -> Unit): InstallResult {
        val size = apk.file.length()
        val result = apk.file.inputStream().buffered().use { input ->
            adb.exec(
                command = "cmd package install -r -t -S $size",
                stdin = input,
                stdinSize = size,
                onProgress = onProgress,
                // 전송이 끝난 뒤 시계가 APK 를 검사·설치하는 데 시간이 걸릴 수 있다.
                timeoutMs = 180_000,
            )
        }
        return InstallResult.parse(result.output, result.exitCode)
    }
}
