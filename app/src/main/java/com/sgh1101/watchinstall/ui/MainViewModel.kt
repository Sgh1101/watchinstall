package com.sgh1101.watchinstall.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sgh1101.watchinstall.adb.FoundService
import com.sgh1101.watchinstall.adb.WatchAdb
import com.sgh1101.watchinstall.adb.WatchDiscovery
import com.sgh1101.watchinstall.install.ApkFile
import com.sgh1101.watchinstall.install.InstallResult
import com.sgh1101.watchinstall.install.WatchInfo
import com.sgh1101.watchinstall.install.WatchInstaller
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val host: String, val port: Int, val info: WatchInfo) : ConnectionState
    data class Failed(val message: String, val needsPairing: Boolean = false) : ConnectionState
}

sealed interface PairingState {
    data object Idle : PairingState
    data object Pairing : PairingState
    data class Failed(val message: String) : PairingState
}

sealed interface InstallState {
    data object Idle : InstallState
    data class Uploading(val progress: Float) : InstallState
    data object Installing : InstallState
    data class Succeeded(val appName: String) : InstallState
    data class Failed(val title: String, val detail: String?, val raw: String?) : InstallState
}

/** 페어링/직접 연결 입력 창 */
data class AddressDialog(
    val mode: Mode,
    val host: String = "",
    val port: String = "",
) {
    enum class Mode { PAIR, CONNECT }
}

data class UiState(
    val discovering: Boolean = false,
    val services: List<FoundService> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val pairing: PairingState = PairingState.Idle,
    val dialog: AddressDialog? = null,
    val pairedBefore: Boolean = false,
    val apk: ApkFile? = null,
    val apkLoading: Boolean = false,
    val apkError: String? = null,
    val install: InstallState = InstallState.Idle,
) {
    val pairingServices get() = services.filter { it.kind == FoundService.Kind.PAIRING }
    val connectServices get() = services.filter { it.kind == FoundService.Kind.CONNECT }
    val isConnected get() = connection is ConnectionState.Connected
    val canInstall get() = isConnected && apk != null &&
        install !is InstallState.Uploading && install !is InstallState.Installing
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("watchinstall", Context.MODE_PRIVATE)
    private val discovery = WatchDiscovery(app)

    private val _state = MutableStateFlow(UiState(pairedBefore = prefs.getBoolean(KEY_PAIRED, false)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 자동 연결을 이미 시도한 주소 (같은 주소로 계속 재시도하지 않도록) */
    private val autoTried = mutableSetOf<String>()
    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            discovery.services.collect { services ->
                _state.update { it.copy(services = services) }
                maybeAutoConnect()
            }
        }
        viewModelScope.launch {
            discovery.running.collect { running -> _state.update { it.copy(discovering = running) } }
        }
        // RSA 키 생성은 느릴 수 있으니 미리 백그라운드에서 준비해 둔다.
        viewModelScope.launch(Dispatchers.IO) { runCatching { WatchAdb.get(app) } }
    }

    private suspend fun adb(): WatchAdb = withContext(Dispatchers.IO) { WatchAdb.get(getApplication()) }

    // ---------------------------------------------------------------- 검색

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()
    fun refreshDiscovery() {
        autoTried.clear()
        discovery.restart()
    }

    // ---------------------------------------------------------------- 입력 창

    fun openPairDialog(service: FoundService?) {
        _state.update {
            it.copy(
                pairing = PairingState.Idle,
                dialog = AddressDialog(
                    AddressDialog.Mode.PAIR,
                    host = service?.host ?: lastHost().orEmpty(),
                    port = service?.port?.toString().orEmpty(),
                ),
            )
        }
    }

    fun openConnectDialog() {
        _state.update {
            it.copy(dialog = AddressDialog(AddressDialog.Mode.CONNECT, host = lastHost().orEmpty()))
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null, pairing = PairingState.Idle) }

    // ---------------------------------------------------------------- 페어링 / 연결

    fun pair(host: String, portText: String, code: String) {
        val port = portText.trim().toIntOrNull()
        if (host.isBlank() || port == null) {
            _state.update { it.copy(pairing = PairingState.Failed("IP 주소와 포트를 확인해 주세요.")) }
            return
        }
        if (!code.trim().matches(Regex("\\d{6}"))) {
            _state.update { it.copy(pairing = PairingState.Failed("페어링 코드는 숫자 6자리예요.")) }
            return
        }
        _state.update { it.copy(pairing = PairingState.Pairing) }
        viewModelScope.launch {
            try {
                adb().pairWith(host.trim(), port, code)
                prefs.edit().putBoolean(KEY_PAIRED, true).putString(KEY_LAST_HOST, host.trim()).apply()
                _state.update { it.copy(pairing = PairingState.Idle, dialog = null, pairedBefore = true) }
                connectAfterPairing(host.trim())
            } catch (e: Exception) {
                Log.w(TAG, "pair failed", e)
                _state.update {
                    it.copy(
                        pairing = PairingState.Failed(
                            "페어링에 실패했어요. 시계의 \"새 기기 페어링\" 화면이 열려 있는지, 코드가 맞는지 확인해 주세요." +
                                (e.message?.let { m -> "\n($m)" } ?: ""),
                        ),
                    )
                }
            }
        }
    }

    /** 페어링 직후: 같은 시계의 연결용 서비스(포트)가 보이면 바로 연결 */
    private suspend fun connectAfterPairing(host: String) {
        autoTried.clear()
        repeat(16) {
            val target = _state.value.connectServices.firstOrNull { it.host == host }
            if (target != null) {
                connect(target.host, target.port)
                return
            }
            delay(500)
        }
        _state.update {
            it.copy(
                connection = ConnectionState.Failed(
                    "페어링은 됐어요! 이제 시계의 무선 디버깅 화면에 보이는 IP 주소와 포트로 연결해 주세요.",
                ),
                dialog = AddressDialog(AddressDialog.Mode.CONNECT, host = host),
            )
        }
    }

    fun connect(service: FoundService) = connect(service.host, service.port)

    fun connectManual(host: String, portText: String) {
        val port = portText.trim().toIntOrNull()
        if (host.isBlank() || port == null) {
            _state.update { it.copy(connection = ConnectionState.Failed("IP 주소와 포트를 확인해 주세요.")) }
            return
        }
        _state.update { it.copy(dialog = null) }
        connect(host.trim(), port)
    }

    fun connect(host: String, port: Int) {
        connectJob?.cancel()
        _state.update { it.copy(connection = ConnectionState.Connecting("$host:$port")) }
        connectJob = viewModelScope.launch {
            try {
                val adb = adb()
                if (!adb.connectTo(host, port)) throw IOException("연결이 거부됐어요.")
                val info = WatchInstaller(adb).readWatchInfo()
                prefs.edit().putBoolean(KEY_PAIRED, true).putString(KEY_LAST_HOST, host).apply()
                _state.update {
                    it.copy(connection = ConnectionState.Connected(host, port, info), pairedBefore = true)
                }
            } catch (e: AdbPairingRequiredException) {
                _state.update {
                    it.copy(connection = ConnectionState.Failed("이 시계와 먼저 페어링해야 해요.", needsPairing = true))
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                _state.update { it.copy(connection = ConnectionState.Failed(connectErrorMessage(e))) }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        viewModelScope.launch {
            adb().disconnectQuietly()
            _state.update { it.copy(connection = ConnectionState.Disconnected) }
        }
    }

    private fun maybeAutoConnect() {
        val s = _state.value
        if (!s.pairedBefore) return
        if (s.connection is ConnectionState.Connected || s.connection is ConnectionState.Connecting) return
        val last = lastHost()
        val target = s.connectServices.firstOrNull { it.host == last } ?: s.connectServices.singleOrNull() ?: return
        if (!autoTried.add(target.address)) return
        connect(target)
    }

    private fun connectErrorMessage(e: Exception): String = when (e) {
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
            "시계에 접속할 수 없어요. 폰과 시계가 같은 Wi-Fi 에 있는지, 무선 디버깅이 켜져 있는지 확인해 주세요."
        is InterruptedException ->
            "시계가 응답하지 않아요. 시계 화면을 켠 상태에서 다시 시도해 주세요."
        else -> "연결에 실패했어요: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun lastHost(): String? = prefs.getString(KEY_LAST_HOST, null)

    // ---------------------------------------------------------------- APK

    fun selectApk(uri: Uri) {
        _state.update { it.copy(apkLoading = true, apkError = null, install = InstallState.Idle) }
        viewModelScope.launch {
            try {
                val apk = ApkFile.load(getApplication(), uri)
                _state.update { it.copy(apk = apk, apkLoading = false) }
            } catch (e: Exception) {
                Log.w(TAG, "apk load failed", e)
                _state.update {
                    it.copy(apk = null, apkLoading = false, apkError = e.message ?: "파일을 읽을 수 없어요.")
                }
            }
        }
    }

    fun install() {
        val s = _state.value
        val apk = s.apk ?: return
        if (s.connection !is ConnectionState.Connected) return
        _state.update { it.copy(install = InstallState.Uploading(0f)) }
        viewModelScope.launch {
            val result = try {
                WatchInstaller(adb()).install(apk) { p ->
                    _state.update {
                        it.copy(install = if (p >= 1f) InstallState.Installing else InstallState.Uploading(p))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                val lost = !adb().isConnected
                _state.update {
                    it.copy(
                        install = InstallState.Failed(
                            title = if (lost) "시계와 연결이 끊겼어요" else "설치 중 오류가 났어요",
                            detail = if (lost) "시계 화면을 켜 두고 다시 연결한 뒤 설치해 주세요." else e.message,
                            raw = null,
                        ),
                        connection = if (lost) ConnectionState.Disconnected else it.connection,
                    )
                }
                if (lost) autoTried.clear()
                return@launch
            }
            _state.update {
                it.copy(
                    install = when (result) {
                        InstallResult.Success -> InstallState.Succeeded(apk.label ?: apk.packageName ?: apk.displayName)
                        is InstallResult.Failure -> InstallState.Failed(
                            title = "설치에 실패했어요",
                            detail = result.explanation,
                            raw = result.raw,
                        )
                    },
                )
            }
        }
    }

    /** 설치한 앱을 시계에서 바로 실행 */
    fun launchOnWatch() {
        val pkg = _state.value.apk?.packageName ?: return
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) return // 셸 명령에 들어가므로 패키지 이름 형식만 허용
        viewModelScope.launch {
            runCatching {
                adb().exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            }.onFailure { Log.w(TAG, "launch failed", it) }
        }
    }

    override fun onCleared() {
        discovery.stop()
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val KEY_PAIRED = "paired"
        private const val KEY_LAST_HOST = "last_host"
    }
}
