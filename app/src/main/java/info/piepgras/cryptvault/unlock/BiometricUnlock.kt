package info.piepgras.cryptvault.unlock

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Shows the system biometric prompt (BUILD_BRIEF.md §4.3): BIOMETRIC_STRONG, with the device
 * credential as an alternative on API 30+ where the Keystore key allows it. The prompt has no
 * CryptoObject: the key is a time-window key, so a successful authentication opens the window
 * and [BiometricWrap] uses the key within it.
 */
object BiometricUnlock {

    sealed class Outcome {
        object Success : Outcome()
        object Cancelled : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    fun prompt(activity: FragmentActivity, title: String, subtitle: String?, negative: String, onOutcome: (Outcome) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOutcome(Outcome.Success)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onOutcome(if (cancelled) Outcome.Cancelled else Outcome.Failed(errString.toString()))
            }
        }
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(allowedAuthenticators())
            .setConfirmationRequired(false)
        subtitle?.let { builder.setSubtitle(it) }
        // A negative button is forbidden together with DEVICE_CREDENTIAL.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) builder.setNegativeButtonText(negative)
        BiometricPrompt(activity, executor, callback).authenticate(builder.build())
    }
}
