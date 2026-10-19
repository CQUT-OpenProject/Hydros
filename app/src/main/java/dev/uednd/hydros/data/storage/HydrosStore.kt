package dev.uednd.hydros.data.storage

import dev.uednd.hydros.data.feedback.*
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.ui.state.DeviceUi
import dev.uednd.hydros.ui.state.SessionUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class SavedAccount(val devices: List<DeviceUi> = emptyList(), val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(), val session: SessionUi? = null,
    val deviceRemarks: Map<String, String> = emptyMap())
@Serializable data class FeedbackSettings(val hapticFeedbackEnabled: Boolean = true, val reduceMotionEnabled: Boolean = false)
@Serializable private data class SavedSettings(val token: String? = null, val lastAccount: String? = null, val theme: String = "SYSTEM", val accounts: Map<String, SavedAccount> = emptyMap(), val onboardingCompleted: Boolean = false,
    val analyticsEnabled: Boolean = false, val feedback: FeedbackSettings = FeedbackSettings(), val fontSizeScale: Float = 1f,
    val postSessionDestination: String = PostSessionDestination.DEVICE.name)
interface HydrosStore {
    suspend fun credentials(): Credentials?
    suspend fun credentials(value: Credentials?)
    suspend fun lastAccount(): String? = null
    suspend fun account(key: String): SavedAccount
    suspend fun updateAccount(key: String, transform: (SavedAccount) -> SavedAccount): SavedAccount
    suspend fun theme(): String
    suspend fun theme(value: String)
    suspend fun feedback(): FeedbackSettings
    suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings): FeedbackSettings
    suspend fun fontSizeScale(): Float
    suspend fun fontSizeScale(value: Float)
    suspend fun onboardingCompleted(): Boolean = false
    suspend fun onboardingCompleted(value: Boolean) {}
    suspend fun analyticsEnabled(): Boolean = false
    suspend fun analyticsEnabled(value: Boolean) {}
    suspend fun postSessionDestination(): PostSessionDestination = PostSessionDestination.DEVICE
    suspend fun postSessionDestination(value: PostSessionDestination) {}
}
private val Context.hydrosPreferences by preferencesDataStore("hydros")
class PreferencesHydrosStore(context: Context) : HydrosStore {
    private val preferences = context.applicationContext.hydrosPreferences
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val secret = CredentialCipher()
    private suspend fun read(): SavedSettings = failureBoundary(FailureOperation.RESTORE, FailureStage.STORAGE) { preferences.data.first()[key]?.let {
        json.decodeFromString<SavedSettings>(it)
    } ?: SavedSettings() }
    private suspend fun edit(transform: (SavedSettings) -> SavedSettings): SavedSettings = failureBoundary(FailureOperation.SETTINGS, FailureStage.STORAGE) { lock.withLock {
        var next: SavedSettings?=null
        preferences.edit { prefs ->
            val existing=prefs[key]?.let { json.decodeFromString<SavedSettings>(it) } ?: SavedSettings()
            val updated=transform(existing)
            prefs[key]=json.encodeToString(updated)
            next=updated
        }
        requireNotNull(next)
    } }
    override suspend fun credentials(): Credentials? {
        val encoded = read().token ?: return null
        return try { json.decodeFromString<Credentials>(secret.decrypt(encoded)) }
        catch (_: Exception) { credentials(null); null }
    }
    override suspend fun credentials(value: Credentials?) { edit { it.copy(token = value?.let { auth -> secret.encrypt(json.encodeToString(auth)) }, lastAccount=value?.key ?: it.lastAccount) } }
    override suspend fun lastAccount()=read().lastAccount
    override suspend fun account(key: String) = read().accounts[key] ?: SavedAccount()
    override suspend fun updateAccount(key: String, transform: (SavedAccount) -> SavedAccount): SavedAccount {
        return edit { it.copy(accounts = it.accounts + (key to transform(it.accounts[key] ?: SavedAccount()))) }.accounts.getValue(key)
    }
    override suspend fun theme() = read().theme
    override suspend fun theme(value: String) { edit { it.copy(theme = value) } }
    override suspend fun feedback() = read().feedback
    override suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings) =
        edit { it.copy(feedback = transform(it.feedback)) }.feedback
    override suspend fun fontSizeScale() = read().fontSizeScale
    override suspend fun fontSizeScale(value: Float) {
        require(FontSizeOption.entries.any { it.scale == value })
        edit { it.copy(fontSizeScale = value) }
    }
    override suspend fun onboardingCompleted() = read().onboardingCompleted
    override suspend fun onboardingCompleted(value: Boolean) { edit { it.copy(onboardingCompleted = value) } }
    override suspend fun analyticsEnabled() = read().analyticsEnabled
    override suspend fun analyticsEnabled(value: Boolean) { edit { it.copy(analyticsEnabled = value) } }
    override suspend fun postSessionDestination(): PostSessionDestination {
        val saved = read().postSessionDestination
        return PostSessionDestination.entries.firstOrNull { it.name == saved } ?: PostSessionDestination.DEVICE
    }
    override suspend fun postSessionDestination(value: PostSessionDestination) { edit { it.copy(postSessionDestination = value.name) } }
}
/** Only ciphertext, IV and authentication tag leave Android Keystore. */
class CredentialCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("hydros.session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("hydros.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun encrypt(value: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(value: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        val parts = value.split(":", limit = 2)
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        String(doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }
}
