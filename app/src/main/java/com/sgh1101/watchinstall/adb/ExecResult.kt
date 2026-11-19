package com.sgh1101.watchinstall.adb

/** 시계에서 실행한 셸 명령의 출력과 종료 코드. */
data class ExecResult(val output: String, val exitCode: Int?) {
    companion object {
        /** 명령 뒤에 `; echo END_MARK$?` 를 붙여 출력이 끝났음을 알아낸다. */
        const val END_MARK = "__WATCHINSTALL_END__"

        private val END = Regex("$END_MARK(-?\\d+)\\s*$")

        fun hasEndMark(text: String): Boolean = END.containsMatchIn(text)

        fun parse(raw: String): ExecResult {
            val match = END.find(raw)
            return if (match != null) {
                ExecResult(raw.substring(0, match.range.first).trimEnd(), match.groupValues[1].toIntOrNull())
            } else {
                ExecResult(raw.trimEnd(), null)
            }
        }
    }
}
