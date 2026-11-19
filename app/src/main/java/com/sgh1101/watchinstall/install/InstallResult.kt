package com.sgh1101.watchinstall.install

/** `cmd package install` 의 결과. */
sealed interface InstallResult {
    data object Success : InstallResult

    data class Failure(
        val code: String,
        val message: String?,
        val raw: String,
    ) : InstallResult {
        /** 사람이 읽을 수 있는 한국어 설명. */
        val explanation: String get() = InstallErrors.explain(code)
    }

    companion object {
        private val FAILURE = Regex("""Failure \[([A-Z0-9_]+)(?::\s*(.*?))?]""", RegexOption.DOT_MATCHES_ALL)
        private val ERROR_LINE = Regex("""(?m)^(?:Error|Exception occurred while executing).*$""")

        /**
         * 출력에서 결과를 뽑아낸다.
         *
         * 예)
         *  - "Success"
         *  - "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.foo signatures do not match ...]"
         *  - "Failure [INSTALL_FAILED_OLDER_SDK]"
         */
        fun parse(output: String, exitCode: Int?): InstallResult {
            val text = output.trim()
            FAILURE.find(text)?.let { m ->
                return Failure(
                    code = m.groupValues[1],
                    message = m.groupValues[2].trim().ifEmpty { null },
                    raw = text,
                )
            }
            if (text.lines().any { it.trim() == "Success" } && (exitCode == null || exitCode == 0)) {
                return Success
            }
            val errorLine = ERROR_LINE.find(text)?.value?.trim()
            return Failure(
                code = "UNKNOWN",
                message = errorLine ?: text.ifEmpty { null },
                raw = text,
            )
        }
    }
}

object InstallErrors {
    private val explanations = mapOf(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to
            "시계에 같은 앱이 다른 서명으로 이미 설치돼 있어요. 시계에서 기존 앱을 지운 뒤 다시 설치해 주세요.",
        "INSTALL_FAILED_VERSION_DOWNGRADE" to
            "시계에 더 높은 버전이 이미 설치돼 있어요. versionCode 를 올리거나 기존 앱을 지운 뒤 설치해 주세요.",
        "INSTALL_FAILED_OLDER_SDK" to
            "이 앱의 minSdk 가 시계의 Android 버전보다 높아요. 앱의 minSdk 를 낮춰서 다시 빌드해 주세요.",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to
            "앱에 시계 CPU(arm64-v8a / armeabi-v7a)용 네이티브 라이브러리가 없어요. ABI 설정을 확인해 주세요.",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to
            "시계 저장 공간이 부족해요. 안 쓰는 앱이나 파일을 지워 주세요.",
        "INSTALL_FAILED_TEST_ONLY" to
            "테스트 전용(testOnly) APK 예요. Android Studio 에서 Build → Build APK(s) 로 만든 APK 를 써 주세요.",
        "INSTALL_FAILED_INVALID_APK" to
            "APK 파일이 올바르지 않아요. 파일이 다 받아졌는지, 분할 APK(.apks)가 아닌지 확인해 주세요.",
        "INSTALL_PARSE_FAILED_NOT_APK" to
            "APK 파일이 아니에요.",
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to
            "APK 가 서명되지 않았어요. 디버그 키나 릴리스 키로 서명된 APK 를 써 주세요.",
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" to
            "APK 서명이 잘못됐거나 기존 앱과 서명이 달라요.",
        "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED" to
            "AndroidManifest.xml 에 문제가 있어요. (예: exported 속성 누락)",
        "INSTALL_FAILED_MISSING_SHARED_LIBRARY" to
            "앱이 필요로 하는 공유 라이브러리가 시계에 없어요. (uses-library 를 required=false 로 바꿔 보세요)",
        "INSTALL_FAILED_DUPLICATE_PERMISSION" to
            "다른 앱이 이미 같은 이름의 권한을 정의하고 있어요.",
        "INSTALL_FAILED_CONFLICTING_PROVIDER" to
            "다른 앱이 이미 같은 authority 의 ContentProvider 를 쓰고 있어요.",
        "INSTALL_FAILED_ABORTED" to
            "설치가 중간에 취소됐어요. 다시 시도해 주세요.",
        "INSTALL_FAILED_USER_RESTRICTED" to
            "시계 설정에서 설치가 막혀 있어요. 시계 화면에 뜬 확인 창이 있으면 허용해 주세요.",
        "INSTALL_FAILED_VERIFICATION_FAILURE" to
            "패키지 검증(Play 프로텍트 등)에서 거부됐어요.",
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION" to
            "targetSdk 가 너무 낮아서 설치가 막혔어요. targetSdk 를 올려서 다시 빌드해 주세요.",
        "INSTALL_FAILED_MISSING_SPLIT" to
            "분할 APK 중 일부만 설치하려고 했어요. 전체 APK(universal APK)로 빌드해 주세요.",
    )

    fun explain(code: String): String =
        explanations[code] ?: if (code == "UNKNOWN") {
            "알 수 없는 오류예요. 아래 원본 메시지를 확인해 주세요."
        } else {
            "설치에 실패했어요 ($code). 아래 원본 메시지를 확인해 주세요."
        }
}
