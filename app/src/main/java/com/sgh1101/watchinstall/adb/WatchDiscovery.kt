package com.sgh1101.watchinstall.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume

/** mDNS 로 찾은 무선 디버깅 서비스 하나. */
data class FoundService(
    val kind: Kind,
    val name: String,
    val host: String,
    val port: Int,
) {
    enum class Kind(val serviceType: String) {
        /** 시계에서 "새 기기 페어링" 화면을 열어 둔 동안만 보인다. */
        PAIRING("_adb-tls-pairing._tcp"),

        /** 무선 디버깅이 켜져 있으면 보인다. 페어링된 기기만 접속 가능. */
        CONNECT("_adb-tls-connect._tcp"),
    }

    val address: String get() = "$host:$port"
}

/**
 * 같은 Wi-Fi 에서 무선 디버깅이 켜진 기기를 찾는다.
 *
 * libadb-android 의 AdbMdns 는 "자기 자신" 만 찾도록 만들어져 있어서 시계 검색에 쓸 수 없다.
 * 그래서 NsdManager 로 직접 검색하고, 반대로 이 폰 자신의 무선 디버깅은 목록에서 뺀다.
 */
class WatchDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolveJob: Job? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _services = MutableStateFlow<List<FoundService>>(emptyList())
    val services: StateFlow<List<FoundService>> = _services.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (_running.value) return
        _running.value = true
        _services.value = emptyList()

        multicastLock = wifi.createMulticastLock("watchinstall-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        // NsdManager.resolveService 는 (API 34 미만에서) 한 번에 하나만 처리할 수 있어서 큐로 순서대로 처리한다.
        resolveJob = scope.launch {
            for (info in resolveQueue) {
                resolve(info)?.let(::onResolved)
            }
        }

        for (kind in FoundService.Kind.entries) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "discovery start failed $serviceType $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    resolveQueue.trySend(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    _services.update { list -> list.filterNot { it.kind == kind && it.name == info.serviceName } }
                }
            }
            listeners += listener
            runCatching { nsd.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { Log.w(TAG, "discoverServices failed", it) }
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        resolveJob?.cancel()
        resolveJob = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /** 검색을 처음부터 다시 한다. (시계에서 페어링 화면을 새로 연 경우 등) */
    fun restart() {
        stop()
        start()
    }

    private fun onResolved(info: NsdServiceInfo) {
        val kind = FoundService.Kind.entries.firstOrNull { info.serviceType.contains(it.serviceType) } ?: return
        val address = hostOf(info) ?: return
        if (isLocalAddress(address)) return // 이 폰 자신의 무선 디버깅은 무시
        val host = address.hostAddress ?: return
        val found = FoundService(kind, info.serviceName, host, info.port)
        _services.update { list ->
            (list.filterNot { it.kind == kind && it.name == found.name } + found)
                .sortedWith(compareBy({ it.kind }, { it.name }))
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        repeat(5) { attempt ->
            val result = suspendCancellableCoroutine<Result<NsdServiceInfo>> { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(Result.failure(ResolveFailed(errorCode)))
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(Result.success(serviceInfo))
                    }
                })
            }
            result.onSuccess { return it }
            val code = (result.exceptionOrNull() as? ResolveFailed)?.code
            if (code != NsdManager.FAILURE_ALREADY_ACTIVE) return null
            delay(200L * (attempt + 1))
        }
        return null
    }

    private class ResolveFailed(val code: Int) : Exception()

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    }

    private fun isLocalAddress(address: InetAddress): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .any { it.hostAddress == address.hostAddress }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "WatchDiscovery"
    }
}
