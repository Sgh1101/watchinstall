package com.sgh1101.watchinstall.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * 이 앱이 ADB 클라이언트로 쓰는 RSA 키와 자체 서명 인증서.
 *
 * 시계는 페어링할 때 이 키를 기억하므로, 한 번 만들면 앱 내부 저장소에 계속 보관한다.
 * (키가 바뀌면 시계와 다시 페어링해야 한다.)
 */
class AdbKeys(val privateKey: PrivateKey, val certificate: Certificate) {

    companion object {
        private const val DIR = "adb"
        private const val KEY_FILE = "adbkey.pk8"
        private const val CERT_FILE = "adbkey.crt"

        fun loadOrCreate(context: Context): AdbKeys {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)

            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = certFile.inputStream().use {
                        CertificateFactory.getInstance("X.509").generateCertificate(it)
                    }
                    return AdbKeys(key, cert)
                }
                // 손상된 경우 새로 만든다.
            }

            val created = generate()
            keyFile.writeBytes(created.privateKey.encoded)
            certFile.writeBytes(created.certificate.encoded)
            return created
        }

        private fun generate(): AdbKeys {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500Name("CN=watchinstall")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - 24L * 60 * 60 * 1000),
                Date(now + 30L * 365 * 24 * 60 * 60 * 1000),
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return AdbKeys(keyPair.private, cert)
        }
    }
}
