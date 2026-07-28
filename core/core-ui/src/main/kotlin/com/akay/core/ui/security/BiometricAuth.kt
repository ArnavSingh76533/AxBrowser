package com.akay.core.ui.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuth {

    fun isAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun Context.findFragmentActivity(): FragmentActivity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Shows the system biometric/device-credential prompt. Calls [onSuccess] or [onFailure].
     * No-ops with [onFailure] if the host Activity isn't a FragmentActivity or biometrics aren't set up.
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            onFailure()
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }

                override fun onAuthenticationFailed() {
                    // Let the user retry; only bail out on explicit error/cancel above.
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(promptInfo)
    }
}
