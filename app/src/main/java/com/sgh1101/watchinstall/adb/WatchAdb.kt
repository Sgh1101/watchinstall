package com.sgh1101.watchinstall.adb

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * 시계 한 대와의 ADB 연결을 관리한다.
 *
 * libadb-android 의 [AbsAdbConnectionManager] 위에 다음을 얹는다.
 *  - 셸 명령 실행 결과를 끝까지 안전하게 읽기 (끝 표시 문자열 사용)
 *  - 큰 데이터를 ADB 규약에 맞게 청크 단위로 보내기
 */
class WatchAdb private constructor(private val keys: AdbKeys) : AbsAdbConnectionManager() {

    init {
        // 시계(Wear OS 5, Android 14) 이상을 가정. 최대 패킷 크기 결정에 쓰인다.
        api = 34
        setTimeout(20, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = keys.privateKey
    override fun getCertificate(): Certificate = keys.certificate
    override fun getDeviceName(): String = "watchinstall"

    /** 무선 디버깅 페어링. 실패하면 예외를 던진다. */
    suspend fun pairWith(host: String, port: Int, code: String) = withContext(Dispatchers.IO) {
        pair(host, port, code.trim())
    }

    /** 이미 연결돼 있으면 끊고 새로 연결한다. */
    suspend fun connectTo(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) runCatching { disconnect() }
        connect(host, port)
    }

    suspend fun disconnectQuietly() = withContext(Dispatchers.IO) {
        runCatching { disconnect() }
        Unit
    }

    /**
     * 셸 명령을 실행하고 출력과 종료 코드를 돌려준다.
     *
     * @param stdin 명령의 표준 입력으로 보낼 데이터 (예: APK 바이트). 없으면 null.
     * @param stdinSize stdin 의 전체 크기 (진행률 계산용)
     * @param onProgress stdin 전송 진행률 콜백 (0.0 ~ 1.0)
     */
    suspend fun exec(
        command: String,
        stdin: InputStream? = null,
        stdinSize: Long = 0,
        onProgress: (Float) -> Unit = {},
        timeoutMs: Long = 30_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        // exec: 서비스는 sh -c 로 실행되므로 명령 뒤에 끝 표시와 종료 코드를 붙인다.
        val stream = openStream("exec:$command; echo ${ExecResult.END_MARK}\$?")
        try {
            if (stdin != null) {
                try {
                    writeChunked(stream, stdin, stdinSize, onProgress)
                } catch (e: IOException) {
                    // 시계가 중간에 오류를 내고 스트림을 닫은 경우: 남은 출력(오류 메시지)이라도 읽어 본다.
                    val partial = runCatching { readUntilEndMark(stream, 3_000) }.getOrDefault("")
                    if (partial.isBlank()) throw e
                    return@withContext ExecResult.parse(partial)
                }
            }
            val raw = readUntilEndMark(stream, timeoutMs)
            ExecResult.parse(raw)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun writeChunked(stream: AdbStream, input: InputStream, total: Long, onProgress: (Float) -> Unit) {
        // AdbStream.write() 는 한 번 호출에 여러 WRTE 패킷을 연달아 보낼 수 있는데,
        // 이는 ADB 규약 위반이라 연결이 끊길 수 있다. 그래서 패킷 하나 크기 이하로 나눠 쓴다.
        val maxData = runCatching { adbConnection?.maxData }.getOrNull() ?: 4096
        val chunk = ByteArray(minOf(maxData, 64 * 1024).coerceAtLeast(1024))
        var sent = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (n == 0) continue
            stream.write(chunk, 0, n)
            sent += n
            if (total > 0) onProgress((sent.toDouble() / total).toFloat().coerceIn(0f, 1f))
        }
        stream.flush()
    }

    /**
     * 끝 표시가 나올 때까지 출력을 읽는다.
     *
     * AdbStream.read() 는 상대가 스트림을 닫은 뒤 남은 데이터를 다 읽으면 영원히 대기할 수 있어서,
     * 읽을 데이터가 있을 때만 읽고 나머지는 짧게 기다리는 방식으로 읽는다.
     */
    private fun readUntilEndMark(stream: AdbStream, timeoutMs: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val available = try {
                stream.available()
            } catch (e: IOException) {
                break // 스트림이 닫혔고 남은 데이터도 없음
            }
            if (available > 0) {
                val n = try {
                    stream.read(buf, 0, minOf(available, buf.size))
                } catch (e: IOException) {
                    break
                }
                if (n < 0) break
                out.write(buf, 0, n)
                if (ExecResult.hasEndMark(out.toString(Charsets.UTF_8.name()))) break
            } else {
                if (stream.isClosed) break
                if (System.currentTimeMillis() > deadline) {
                    throw IOException("시계 응답 시간이 초과됐어요.")
                }
                Thread.sleep(20)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        @Volatile
        private var instance: WatchAdb? = null

        fun get(context: Context): WatchAdb =
            instance ?: synchronized(this) {
                instance ?: WatchAdb(AdbKeys.loadOrCreate(context.applicationContext)).also { instance = it }
            }
    }
}
