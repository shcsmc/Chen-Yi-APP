package com.beiwang.memo.ui.vault

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import javax.crypto.Cipher

/**
 * 指纹（强生物识别）。用系统自带的 BiometricPrompt，不加第三方库。
 * 只接受「强」生物识别（一般是指纹）：很多手机的人脸解锁安全等级不够，系统不让它解开加密密钥。
 */
object Biometric {

    fun available(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 ->
            context.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT == 29 ->
            @Suppress("DEPRECATION")
            context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT == 28 -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        else -> false
    }

    /**
     * 弹出系统指纹框。验证通过后 [onSuccess] 拿到已授权的 Cipher；
     * 用户点「用密码」或取消时 [onCancel]；其他错误 [onError]（带系统给的原因）。
     */
    fun authenticate(
        context: Context,
        cipher: Cipher,
        title: String,
        onSuccess: (Cipher) -> Unit,
        onCancel: () -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT < 28) return null
        val executor = context.mainExecutor
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setNegativeButton("用密码", executor) { _, _ -> onCancel() }
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }
            .build()
        val cancel = CancellationSignal()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher), cancel, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c != null) onSuccess(c) else onError("系统没有返回授权结果")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 点「用密码」走的是 setNegativeButton 的回调，不会到这里
                    if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    ) onCancel() else onError(errString.toString())
                }
            },
        )
        return cancel
    }
}
