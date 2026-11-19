package com.sgh1101.watchinstall

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class WatchInstallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 무선 디버깅 페어링은 TLS 1.3 + keying material export 가 필요하다.
        // 기기 내장 Conscrypt 대신 번들된 Conscrypt 를 최우선 공급자로 등록한다.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
