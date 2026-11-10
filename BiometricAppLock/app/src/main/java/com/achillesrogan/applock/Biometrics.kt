package com.achillesrogan.applock

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Strong-biometric gate bound to an Android Keystore key.
 *
 * The prompt callback is not enough: after authentication the cipher has to
 * encrypt a challenge. Enrolling or removing a fingerprint invalidates the key.
 */
object Biometrics {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.achillesrogan.applock.biometric"
    private const val TRANSFORMATION =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}"
    private const val PREFS = "biometric_gate"
    private const val KEY_REARM = "rearm_pending"

    /** Not secret. doFinal only proves the keystore released the key. */
    private val CHALLENGE = "applock-biometric-ok".toByteArray(Charsets.UTF_8)

    private val cryptoLock = Any()

    fun status(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)

    fun isAvailable(context: Context) = status(context) == BiometricManager.BIOMETRIC_SUCCESS

    /** Human-readable reason biometrics can't be used, or null if they can. */
    fun unavailableReason(context: Context): String? = when (status(context)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> context.getString(R.string.no_biometric_enrolled)
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> context.getString(R.string.no_biometric_hardware)
        else -> context.getString(R.string.biometric_unavailable)
    }

    /**
     * @param onSuccess called only after the keystore cipher runs. The flag is true when
     * fingerprint enrollment changed and this authentication re-armed a new key.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        onSuccess: (enrollmentChanged: Boolean) -> Unit,
        onCancel: () -> Unit,
        onError: (CharSequence) -> Unit,
    ) {
        if (!isAvailable(activity)) {
            onError(unavailableReason(activity) ?: activity.getString(R.string.biometric_unavailable))
            return
        }
        // Keystore work stays on the main thread so the prompt cannot outlive a destroyed activity.
        val prepared = try {
            synchronized(cryptoLock) { prepare(activity.applicationContext) }
        } catch (e: Exception) {
            onError(activity.getString(R.string.keystore_error))
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        when (prepared) {
            is Prepared.Ready -> showPrompt(activity, title, prepared, onSuccess, onCancel, onError)
            is Prepared.Failed -> onError(prepared.message)
        }
    }

    private sealed class Prepared {
        data class Ready(val cipher: Cipher, val enrollmentChanged: Boolean) : Prepared()
        data class Failed(val message: String) : Prepared()
    }

    private fun prepare(context: Context): Prepared {
        try {
            return Prepared.Ready(initCipher(), enrollmentChanged = isRearmPending(context))
        } catch (e: Exception) {
            if (!isInvalidated(e)) {
                return Prepared.Failed(context.getString(R.string.keystore_error))
            }
        }
        // The previous key is dead. Replace it so this prompt can be bound to a cipher.
        // The new key still requires a per-use strong biometric; nothing unlocks until doFinal.
        return try {
            deleteKey()
            setRearmPending(context, true)
            Prepared.Ready(initCipher(), enrollmentChanged = true)
        } catch (e: Exception) {
            Prepared.Failed(context.getString(R.string.keystore_error))
        }
    }

    private fun isInvalidated(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is KeyPermanentlyInvalidatedException || current is UnrecoverableKeyException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun showPrompt(
        activity: FragmentActivity,
        title: String,
        prepared: Prepared.Ready,
        onSuccess: (Boolean) -> Unit,
        onCancel: () -> Unit,
        onError: (CharSequence) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val verified = synchronized(cryptoLock) { verify(result) }
                if (!verified) {
                    onError(activity.getString(R.string.auth_not_verified))
                    return
                }
                setRearmPending(activity, false)
                onSuccess(prepared.enrollmentChanged)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> onCancel()
                    else -> onError(errString)
                }
            }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(activity.getString(R.string.cancel))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
        if (prepared.enrollmentChanged) {
            info.setSubtitle(activity.getString(R.string.biometric_invalidated_subtitle))
            info.setDescription(activity.getString(R.string.biometric_invalidated_detail))
        } else {
            info.setSubtitle(activity.getString(R.string.unlock_subtitle))
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        try {
            prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(prepared.cipher))
        } catch (e: Exception) {
            onError(activity.getString(R.string.keystore_error))
        }
    }

    private fun verify(result: BiometricPrompt.AuthenticationResult): Boolean {
        if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) {
            return false
        }
        val cipher = result.cryptoObject?.cipher ?: return false
        return try {
            cipher.doFinal(CHALLENGE)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun initCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return cipher
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            keyStore.deleteEntry(KEY_ALIAS)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 = authenticate on every use, and only a class-3 biometric can authorize the key.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun deleteKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isRearmPending(context: Context) = prefs(context).getBoolean(KEY_REARM, false)

    private fun setRearmPending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_REARM, pending).apply()
    }
}
