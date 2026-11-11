package com.dsh.paperink.host

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * 插件签名白名单。
 *
 * 规则（两条，任一满足即放行）：
 *  1. 证书摘要与宿主自己相同 —— 相当于「同一个开发者签的名」，debug 包天然满足，
 *     方便本地联调；正式分发时这条等于「只有我们自己签的插件能装进来」。
 *  2. 摘要命中显式白名单 [allowedDigests] —— 给第三方开发者留的口子，
 *     我们把某个证书指纹写进代码才算授权。
 *
 * 注意：这里校验的是**签名证书**，不是包名。包名可以随便改，证书改不了。
 */
object PluginSignaturePolicy {

    private const val TAG = "PaperInkPlugin"

    /** 外部开发者证书指纹（SHA-256，小写十六进制，无冒号）。留空 = 只信任自家签名。 */
    val allowedDigests: Set<String> = emptySet()

    fun sha256(context: Context, packageName: String): String? = try {
        val pm = context.packageManager
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo
            if (signing == null) emptyArray() else if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        sigs.firstOrNull()?.let { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    } catch (e: Exception) {
        Log.w(TAG, "signature read failed pkg=$packageName", e)
        null
    }

    fun hostDigest(context: Context): String? = sha256(context, context.packageName)

    fun isTrusted(context: Context, packageName: String): Boolean {
        val digest = sha256(context, packageName) ?: return reject(packageName, "no-signature")
        if (digest in allowedDigests) return true
        val host = hostDigest(context)
        if (host != null && digest == host) return true
        return reject(packageName, "untrusted:$digest")
    }

    private fun reject(pkg: String, why: String): Boolean {
        Log.w(TAG, "plugin rejected pkg=$pkg reason=$why")
        return false
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}