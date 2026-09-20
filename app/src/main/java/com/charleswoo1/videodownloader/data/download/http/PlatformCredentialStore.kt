package com.charleswoo1.videodownloader.data.download.http

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.charleswoo1.videodownloader.domain.model.Platform
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface PlatformCredentialStore {
    fun saveCookies(platform: Platform, cookies: List<Cookie>)
    fun getCookies(platform: Platform): List<Cookie>
    fun clearCookies(platform: Platform)
    fun getStatus(platform: Platform): PlatformSessionInfo
    fun updateStatus(platform: Platform, state: SessionState, details: String? = null)
}

/**
 * In-memory credential store for deterministic unit testing and environments without Android Keystore.
 */
class InMemoryPlatformCredentialStore : PlatformCredentialStore {
    private val cookieStore = ConcurrentHashMap<Platform, List<Cookie>>()
    private val statusStore = ConcurrentHashMap<Platform, PlatformSessionInfo>()

    override fun saveCookies(platform: Platform, cookies: List<Cookie>) {
        cookieStore[platform] = cookies.toList()
        statusStore[platform] = PlatformSessionInfo(
            platform = platform,
            state = if (cookies.isNotEmpty()) SessionState.CONFIGURED else SessionState.NOT_CONFIGURED,
            cookieCount = cookies.size,
            lastUpdatedMs = System.currentTimeMillis(),
            details = if (cookies.isNotEmpty()) "已匯入，待驗證" else null
        )
    }

    override fun getCookies(platform: Platform): List<Cookie> {
        return cookieStore[platform] ?: emptyList()
    }

    override fun clearCookies(platform: Platform) {
        cookieStore.remove(platform)
        statusStore[platform] = PlatformSessionInfo(
            platform = platform,
            state = SessionState.NOT_CONFIGURED,
            cookieCount = 0,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    override fun getStatus(platform: Platform): PlatformSessionInfo {
        return statusStore[platform] ?: PlatformSessionInfo(
            platform = platform,
            state = SessionState.NOT_CONFIGURED
        )
    }

    override fun updateStatus(platform: Platform, state: SessionState, details: String?) {
        val current = getStatus(platform)
        statusStore[platform] = current.copy(
            state = state,
            lastUpdatedMs = System.currentTimeMillis(),
            details = details
        )
    }
}

/**
 * Android Keystore-backed encrypted credential store using AES-256-GCM.
 * Hardware-secured on supported Android devices; falls back gracefully to in-memory if Keystore is absent.
 */
class EncryptedPlatformCredentialStore(
    private val context: Context,
    private val prefsName: String = "platform_credentials_secure"
) : PlatformCredentialStore {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "SocialVideoDownloaderMasterKey"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val PREF_KEY_PREFIX_CIPHER = "cipher_"
        private const val PREF_KEY_PREFIX_IV = "iv_"
        private const val PREF_KEY_PREFIX_STATE = "state_"
        private const val PREF_KEY_PREFIX_COUNT = "count_"
        private const val PREF_KEY_PREFIX_UPDATED = "updated_"
        private const val PREF_KEY_PREFIX_DETAILS = "details_"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private val memoryFallback: InMemoryPlatformCredentialStore by lazy {
        InMemoryPlatformCredentialStore()
    }

    private val isKeystoreUsable: Boolean by lazy {
        try {
            getOrCreateSecretKey() != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            return entry?.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    override fun saveCookies(platform: Platform, cookies: List<Cookie>) {
        if (!isKeystoreUsable) {
            memoryFallback.saveCookies(platform, cookies)
            return
        }

        try {
            val key = getOrCreateSecretKey() ?: throw IllegalStateException("KeyStore key unavailable")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val jsonString = serializeCookies(cookies)
            val cipherBytes = cipher.doFinal(jsonString.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            prefs.edit()
                .putString("${PREF_KEY_PREFIX_CIPHER}${platform.name}", cipherBase64)
                .putString("${PREF_KEY_PREFIX_IV}${platform.name}", ivBase64)
                .putString("${PREF_KEY_PREFIX_STATE}${platform.name}", SessionState.CONFIGURED.name)
                .putInt("${PREF_KEY_PREFIX_COUNT}${platform.name}", cookies.size)
                .putLong("${PREF_KEY_PREFIX_UPDATED}${platform.name}", System.currentTimeMillis())
                .putString("${PREF_KEY_PREFIX_DETAILS}${platform.name}", "已匯入，待驗證")
                .apply()
        } catch (e: Throwable) {
            memoryFallback.saveCookies(platform, cookies)
        }
    }

    override fun getCookies(platform: Platform): List<Cookie> {
        if (!isKeystoreUsable) {
            return memoryFallback.getCookies(platform)
        }

        try {
            val cipherBase64 = prefs.getString("${PREF_KEY_PREFIX_CIPHER}${platform.name}", null) ?: return emptyList()
            val ivBase64 = prefs.getString("${PREF_KEY_PREFIX_IV}${platform.name}", null) ?: return emptyList()

            val cipherBytes = Base64.decode(cipherBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val key = getOrCreateSecretKey() ?: return emptyList()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            val decryptedBytes = cipher.doFinal(cipherBytes)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            return deserializeCookies(jsonString)
        } catch (_: Throwable) {
            return memoryFallback.getCookies(platform)
        }
    }

    override fun clearCookies(platform: Platform) {
        if (!isKeystoreUsable) {
            memoryFallback.clearCookies(platform)
            return
        }

        prefs.edit()
            .remove("${PREF_KEY_PREFIX_CIPHER}${platform.name}")
            .remove("${PREF_KEY_PREFIX_IV}${platform.name}")
            .putString("${PREF_KEY_PREFIX_STATE}${platform.name}", SessionState.NOT_CONFIGURED.name)
            .putInt("${PREF_KEY_PREFIX_COUNT}${platform.name}", 0)
            .putLong("${PREF_KEY_PREFIX_UPDATED}${platform.name}", System.currentTimeMillis())
            .remove("${PREF_KEY_PREFIX_DETAILS}${platform.name}")
            .apply()
    }

    override fun getStatus(platform: Platform): PlatformSessionInfo {
        if (!isKeystoreUsable) {
            return memoryFallback.getStatus(platform)
        }

        val stateStr = prefs.getString("${PREF_KEY_PREFIX_STATE}${platform.name}", SessionState.NOT_CONFIGURED.name)
        val state = try {
            SessionState.valueOf(stateStr ?: SessionState.NOT_CONFIGURED.name)
        } catch (_: Exception) {
            SessionState.NOT_CONFIGURED
        }
        val count = prefs.getInt("${PREF_KEY_PREFIX_COUNT}${platform.name}", 0)
        val updated = prefs.getLong("${PREF_KEY_PREFIX_UPDATED}${platform.name}", 0L)
        val details = prefs.getString("${PREF_KEY_PREFIX_DETAILS}${platform.name}", null)

        return PlatformSessionInfo(
            platform = platform,
            state = state,
            cookieCount = count,
            lastUpdatedMs = updated,
            details = details
        )
    }

    override fun updateStatus(platform: Platform, state: SessionState, details: String?) {
        if (!isKeystoreUsable) {
            memoryFallback.updateStatus(platform, state, details)
            return
        }

        val editor = prefs.edit()
            .putString("${PREF_KEY_PREFIX_STATE}${platform.name}", state.name)
            .putLong("${PREF_KEY_PREFIX_UPDATED}${platform.name}", System.currentTimeMillis())

        if (details != null) {
            editor.putString("${PREF_KEY_PREFIX_DETAILS}${platform.name}", details)
        } else {
            editor.remove("${PREF_KEY_PREFIX_DETAILS}${platform.name}")
        }
        editor.apply()
    }

    private fun serializeCookies(cookies: List<Cookie>): String {
        val array = JSONArray()
        for (c in cookies) {
            val obj = JSONObject().apply {
                put("name", c.name)
                put("value", c.value)
                put("domain", c.domain)
                put("path", c.path)
                put("secure", c.secure)
                put("httpOnly", c.httpOnly)
                put("expiresAt", c.expiresAt)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeCookies(jsonString: String): List<Cookie> {
        val result = mutableListOf<Cookie>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val value = obj.optString("value")
                val domain = obj.optString("domain")
                val path = obj.optString("path", "/")
                val secure = obj.optBoolean("secure", false)
                val httpOnly = obj.optBoolean("httpOnly", false)
                val expiresAt = obj.optLong("expiresAt", 0L)

                val builder = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path(path)

                if (secure) builder.secure()
                if (httpOnly) builder.httpOnly()
                if (expiresAt > 0L) builder.expiresAt(expiresAt)

                result.add(builder.build())
            }
        } catch (_: Exception) {}
        return result
    }
}
