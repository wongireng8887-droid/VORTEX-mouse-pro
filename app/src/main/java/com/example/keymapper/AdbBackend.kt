package com.example.keymapper

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateExtensions
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.KeyIdentifier
import sun.security.x509.PrivateKeyUsageExtension
import sun.security.x509.SubjectKeyIdentifierExtension
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

class AdbMgr(private val ctx: Context) : AbsAdbConnectionManager() {
    private var pk: PrivateKey
    private var cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kf = File(ctx.filesDir, "adb_key.pk8")
        val cf = File(ctx.filesDir, "adb_cert.der")
        var loadedKey: PrivateKey? = null
        var loadedCert: Certificate? = null
        try {
            if (kf.exists() && cf.exists()) {
                loadedKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                loadedCert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cf.readBytes()))
            }
        } catch (e: Exception) {}
        if (loadedKey != null && loadedCert != null) {
            pk = loadedKey
            cert = loadedCert
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val kp = kpg.generateKeyPair()
            val publicKey = kp.public
            val privateKey = kp.private
            val algorithmName = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 3650L * 86400000L)
            val exts = CertificateExtensions()
            exts.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
            val x500 = X500Name("CN=VortexMousePro")
            exts.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
            info.set("subject", CertificateSubjectName(x500))
            info.set("key", CertificateX509Key(publicKey))
            info.set("validity", CertificateValidity(notBefore, notAfter))
            info.set("issuer", CertificateIssuerName(x500))
            info.set("extensions", exts)
            val impl = X509CertImpl(info)
            impl.sign(privateKey, algorithmName)
            pk = privateKey
            cert = impl
            try {
                kf.writeBytes(privateKey.encoded)
                cf.writeBytes(impl.encoded)
            } catch (e: Exception) {}
        }
    }

    override fun getPrivateKey(): PrivateKey = pk
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "VortexMousePro"
}

object AdbBackend {
    private var mgr: AdbMgr? = null

    @Synchronized
    fun manager(ctx: Context): AdbMgr {
        var m = mgr
        if (m == null) { m = AdbMgr(ctx.applicationContext); mgr = m }
        return m
    }

    fun paired(ctx: Context): Boolean = File(ctx.filesDir, "adb_key.pk8").exists()

    fun wifiDebugOn(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
    } catch (e: Exception) { false }

    fun canTry(ctx: Context): Boolean = paired(ctx) && wifiDebugOn(ctx)

    fun connected(): Boolean = try { mgr?.isConnected == true } catch (e: Exception) { false }

    fun open(ctx: Context, cmd: String): Injector.Conn {
        val m = manager(ctx)
        if (!m.isConnected) {
            if (!m.autoConnect(ctx, 10000)) throw IOException("Debug nirkabel belum tersambung")
        }
        val s = m.openStream("shell:$cmd")
        val os = s.openOutputStream()
        val ins = s.openInputStream()
        return Injector.Conn(ins, os, null) { try { s.close() } catch (e: Exception) {} }
    }
}
