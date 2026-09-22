package com.charleswoo1.videodownloader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.charleswoo1.videodownloader.data.download.DownloadEngine
import com.charleswoo1.videodownloader.data.download.DownloadRepository
import com.charleswoo1.videodownloader.data.download.http.AuthenticatedPlatformSessionProvider
import com.charleswoo1.videodownloader.data.download.http.InMemoryPlatformCredentialStore
import com.charleswoo1.videodownloader.data.download.http.SessionState
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.ui.preferences.UiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UiPreferencesAndSettingsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class Editor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { temp[key ?: ""] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { temp[key ?: ""] = null; return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) values.clear()
                for ((k, v) in temp) {
                    if (v == null) values.remove(k) else values[k] = v
                }
            }
        }
    }

    // 1. show_debug_ui default false
    @Test
    fun showDebugUi_defaultFalse() {
        val fakePrefs = FakeSharedPreferences()
        val preferences = UiPreferences(fakePrefs)

        assertFalse("show_debug_ui must default to false", preferences.showDebugUi)
        assertEquals(false, fakePrefs.getBoolean(UiPreferences.KEY_SHOW_DEBUG_UI, false))
    }

    // 2. set true persists
    @Test
    fun showDebugUi_setTruePersists() {
        val fakePrefs = FakeSharedPreferences()
        val preferences = UiPreferences(fakePrefs)

        preferences.showDebugUi = true

        assertTrue("showDebugUi getter should return true after setting true", preferences.showDebugUi)
        assertTrue("SharedPreferences should persist true under KEY_SHOW_DEBUG_UI", fakePrefs.getBoolean(UiPreferences.KEY_SHOW_DEBUG_UI, false))
    }

    // 3. set false persists
    @Test
    fun showDebugUi_setFalsePersists() {
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.edit().putBoolean(UiPreferences.KEY_SHOW_DEBUG_UI, true).apply()
        val preferences = UiPreferences(fakePrefs)
        assertTrue("Precondition: initially true", preferences.showDebugUi)

        preferences.showDebugUi = false

        assertFalse("showDebugUi getter should return false after setting false", preferences.showDebugUi)
        assertFalse("SharedPreferences should persist false under KEY_SHOW_DEBUG_UI", fakePrefs.getBoolean(UiPreferences.KEY_SHOW_DEBUG_UI, true))
    }

    // 4. MainViewModel exposes persisted value
    @Test
    fun mainViewModel_exposesPersistedValue() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        fakePrefs.edit().putBoolean(UiPreferences.KEY_SHOW_DEBUG_UI, true).apply()
        val preferences = UiPreferences(fakePrefs)

        // Setup test repository state to avoid Android Context initialization issues
        val mockEngine = object : DownloadEngine {
            override fun isInitialized(): Boolean = true
            override suspend fun extractMediaInfo(url: String) = Result.failure<MediaInfo>(Exception())
            override suspend fun download(
                request: DownloadRequest,
                destDir: File,
                onProgress: (Float, Long?, String?) -> Unit,
                onStatus: (String) -> Unit
            ) = Result.failure<File>(Exception())
            override fun cancelDownload() {}
        }
        val testStore = InMemoryPlatformCredentialStore().apply {
            updateStatus(Platform.INSTAGRAM, SessionState.ACTIVE, "test")
        }
        DownloadRepository.setSessionProviderForTesting(AuthenticatedPlatformSessionProvider(testStore))
        DownloadRepository.setEngineForTesting(mockEngine)

        val appInfo = android.content.pm.ApplicationInfo().apply {
            nativeLibraryDir = System.getProperty("java.io.tmpdir") ?: ""
        }
        val testApp = object : Application() {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo = appInfo
            override fun getNoBackupFilesDir(): File = File(System.getProperty("java.io.tmpdir") ?: ".")
        }

        val viewModel = MainViewModel(testApp, preferences)

        // Initial value matches persisted preferences
        assertTrue("MainViewModel.showDebugUi should expose persisted true", viewModel.showDebugUi.value)

        // Setting false updates StateFlow and persists to preferences
        viewModel.setShowDebugUi(false)
        assertFalse("MainViewModel.showDebugUi should update to false", viewModel.showDebugUi.value)
        assertFalse("Preferences should persist false", preferences.showDebugUi)

        // Setting true updates StateFlow and persists to preferences
        viewModel.setShowDebugUi(true)
        assertTrue("MainViewModel.showDebugUi should update to true", viewModel.showDebugUi.value)
        assertTrue("Preferences should persist true", preferences.showDebugUi)
    }

    // 5. 所有 Session ACTIVE -> login reminder condition false
    @Test
    fun loginReminder_allActive_returnsFalse() {
        val shouldShow = LoginReminderHelper.shouldShowReminder(
            instagramState = SessionState.ACTIVE,
            threadsState = SessionState.ACTIVE,
            xState = SessionState.ACTIVE
        )
        assertFalse("When all platforms are ACTIVE, reminder must not be shown", shouldShow)
        assertEquals(0, LoginReminderHelper.countInactivePlatforms(SessionState.ACTIVE, SessionState.ACTIVE, SessionState.ACTIVE))
    }

    // 6. 任一 NOT_CONFIGURED -> reminder true
    @Test
    fun loginReminder_anyNotConfigured_returnsTrue() {
        assertTrue(
            "Instagram NOT_CONFIGURED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.NOT_CONFIGURED, SessionState.ACTIVE, SessionState.ACTIVE)
        )
        assertTrue(
            "Threads NOT_CONFIGURED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.ACTIVE, SessionState.NOT_CONFIGURED, SessionState.ACTIVE)
        )
        assertTrue(
            "X NOT_CONFIGURED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.ACTIVE, SessionState.ACTIVE, SessionState.NOT_CONFIGURED)
        )
        assertEquals(1, LoginReminderHelper.countInactivePlatforms(SessionState.NOT_CONFIGURED, SessionState.ACTIVE, SessionState.ACTIVE))
        assertEquals(2, LoginReminderHelper.countInactivePlatforms(SessionState.NOT_CONFIGURED, SessionState.NOT_CONFIGURED, SessionState.ACTIVE))
        assertEquals(3, LoginReminderHelper.countInactivePlatforms(SessionState.NOT_CONFIGURED, SessionState.NOT_CONFIGURED, SessionState.NOT_CONFIGURED))
    }

    // 7. CONFIGURED -> reminder true
    @Test
    fun loginReminder_configured_returnsTrue() {
        assertTrue(
            "Instagram CONFIGURED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.CONFIGURED, SessionState.ACTIVE, SessionState.ACTIVE)
        )
        assertTrue(
            "Threads CONFIGURED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.ACTIVE, SessionState.CONFIGURED, SessionState.ACTIVE)
        )
        assertTrue(
            "X CONFIGURED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.ACTIVE, SessionState.ACTIVE, SessionState.CONFIGURED)
        )
    }

    // 8. EXPIRED -> reminder true
    @Test
    fun loginReminder_expired_returnsTrue() {
        assertTrue(
            "Instagram EXPIRED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.EXPIRED, SessionState.ACTIVE, SessionState.ACTIVE)
        )
        assertTrue(
            "Threads EXPIRED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.ACTIVE, SessionState.EXPIRED, SessionState.ACTIVE)
        )
        assertTrue(
            "X EXPIRED should trigger reminder",
            LoginReminderHelper.shouldShowReminder(SessionState.ACTIVE, SessionState.ACTIVE, SessionState.EXPIRED)
        )
    }

    // 9. debug OFF -> diagnostic UI condition false
    @Test
    fun diagnosticsUi_debugOff_returnsFalse() {
        assertFalse(
            "When showDebugUi is false, diagnostics UI condition must be false",
            LoginReminderHelper.shouldShowDiagnostics(false)
        )
    }

    // 10. debug ON -> diagnostic UI condition true
    @Test
    fun diagnosticsUi_debugOn_returnsTrue() {
        assertTrue(
            "When showDebugUi is true, diagnostics UI condition must be true",
            LoginReminderHelper.shouldShowDiagnostics(true)
        )
    }

    // Extra: reminder message content formatting
    @Test
    fun reminderMessage_formatting() {
        val singleMissing = LoginReminderHelper.getReminderMessage(1)
        assertEquals("尚有 1 個平台未連線。登入後可下載受限或年齡限制內容。", singleMissing)

        val twoMissing = LoginReminderHelper.getReminderMessage(2)
        assertEquals("尚有 2 個平台未連線。登入後可下載受限或年齡限制內容。", twoMissing)

        val threeMissing = LoginReminderHelper.getReminderMessage(3)
        assertEquals("尚有 3 個平台未連線。登入後可下載受限或年齡限制內容。", threeMissing)
    }
}
