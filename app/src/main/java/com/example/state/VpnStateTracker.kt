package com.example.state

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnStateTracker {

    private const val PREFS_NAME = "TPMODZ_PREFS"
    private const val KEY_SELECTED_PACKAGES = "selected_packages"
    private const val KEY_FAKE_PING_VAL = "fake_ping_val"
    private const val KEY_FAKE_PING_ENABLED = "fake_ping_enabled"
    private const val KEY_LAG_MODE = "lag_mode"
    private const val KEY_OVERLAY_ACTIVE = "overlay_active"
    private const val KEY_CUT_SECONDS = "cut_seconds"
    private const val KEY_CUT_MILLIS = "cut_millis"
    private const val KEY_IS_INFINITE = "is_infinite"
    private const val KEY_IS_ALL_APPS = "is_all_apps"
    private const val KEY_SETUP_COMPLETED = "setup_completed"

    // ================= STATE =================

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive = _isVpnActive.asStateFlow()

    private val _isAllAppsEnabled = MutableStateFlow(false)
    val isAllAppsEnabled = _isAllAppsEnabled.asStateFlow()

    private val _setupCompleted = MutableStateFlow(false)
    val setupCompleted = _setupCompleted.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages = _selectedPackages.asStateFlow()

    private val _fakePingValue = MutableStateFlow(120)
    val fakePingValue = _fakePingValue.asStateFlow()

    private val _isFakePingEnabled = MutableStateFlow(false)
    val isFakePingEnabled = _isFakePingEnabled.asStateFlow()

    private val _lagMode = MutableStateFlow("Both")
    val lagMode = _lagMode.asStateFlow()

    private val _cutSeconds = MutableStateFlow(5)
    val cutSeconds = _cutSeconds.asStateFlow()

    private val _cutMillis = MutableStateFlow(0)
    val cutMillis = _cutMillis.asStateFlow()

    private val _isInfinite = MutableStateFlow(false)
    val isInfinite = _isInfinite.asStateFlow()

    private val _isDisconnecting = MutableStateFlow(false)
    val isDisconnecting = _isDisconnecting.asStateFlow()

    private val _disconnectMillisLeft = MutableStateFlow(0)
    val disconnectMillisLeft = _disconnectMillisLeft.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive = _isOverlayActive.asStateFlow()

    private val _currentLivePing = MutableStateFlow(0)
    val currentLivePing = _currentLivePing.asStateFlow()

    private var appContext: Context? = null

    // ================= INIT =================

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        _selectedPackages.value =
            prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet())?.toSet() ?: emptySet()

        _fakePingValue.value = prefs.getInt(KEY_FAKE_PING_VAL, 120)
        _isFakePingEnabled.value = prefs.getBoolean(KEY_FAKE_PING_ENABLED, false)
        _lagMode.value = prefs.getString(KEY_LAG_MODE, "Both") ?: "Both"
        _cutSeconds.value = prefs.getInt(KEY_CUT_SECONDS, 5)
        _cutMillis.value = prefs.getInt(KEY_CUT_MILLIS, 0)
        _isInfinite.value = prefs.getBoolean(KEY_IS_INFINITE, false)
        _isOverlayActive.value = prefs.getBoolean(KEY_OVERLAY_ACTIVE, false)
        _isAllAppsEnabled.value = prefs.getBoolean(KEY_IS_ALL_APPS, false)
        _setupCompleted.value = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    }

    // ================= VPN =================

    fun setVpnActive(active: Boolean) {
        _isVpnActive.value = active
    }

    fun setAllAppsEnabled(context: Context, enabled: Boolean) {
        _isAllAppsEnabled.value = enabled
        saveBool(context, KEY_IS_ALL_APPS, enabled)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        _setupCompleted.value = completed
        saveBool(context, KEY_SETUP_COMPLETED, completed)
    }

    // ================= APP SELECTION =================

    fun toggleApp(packageName: String) {
        val set = _selectedPackages.value.toMutableSet()
        if (set.contains(packageName)) {
            set.remove(packageName)
        } else {
            set.add(packageName)
        }
        _selectedPackages.value = set
        appContext?.let { saveSet(it, KEY_SELECTED_PACKAGES, set) }
    }

    fun toggleSelectedPackage(context: Context, packageName: String) {
        val set = _selectedPackages.value.toMutableSet()

        if (set.contains(packageName)) set.remove(packageName)
        else set.add(packageName)

        _selectedPackages.value = set
        saveSet(context, KEY_SELECTED_PACKAGES, set)
    }

    fun selectAllPackages(context: Context, packages: List<String>) {
        val set = packages.toSet()
        _selectedPackages.value = set
        saveSet(context, KEY_SELECTED_PACKAGES, set)
    }

    fun clearAllPackages(context: Context) {
        _selectedPackages.value = emptySet()
        saveSet(context, KEY_SELECTED_PACKAGES, emptySet())
    }

    // ================= FAKE PING =================

    fun setFakePingValue(context: Context, value: Int) {
        _fakePingValue.value = value
        saveInt(context, KEY_FAKE_PING_VAL, value)
    }

    fun setFakePingEnabled(context: Context, enabled: Boolean) {
        _isFakePingEnabled.value = enabled
        saveBool(context, KEY_FAKE_PING_ENABLED, enabled)
    }

    // ================= LAG MODE =================

    fun setLagMode(context: Context, mode: String) {
        _lagMode.value = mode
        saveString(context, KEY_LAG_MODE, mode)
    }

    // ================= CUT TIMER =================

    fun setCutSeconds(context: Context, secs: Int) {
        _cutSeconds.value = secs
        saveInt(context, KEY_CUT_SECONDS, secs)
    }

    fun setCutMillis(context: Context, ms: Int) {
        _cutMillis.value = ms
        saveInt(context, KEY_CUT_MILLIS, ms)
    }

    fun setIsInfinite(context: Context, infinite: Boolean) {
        _isInfinite.value = infinite
        saveBool(context, KEY_IS_INFINITE, infinite)
    }

    // ================= DISCONNECT LOGIC (FIXED) =================

    fun setDisconnecting(active: Boolean, millis: Int = 0) {
        _isDisconnecting.value = active
        _disconnectMillisLeft.value = millis
    }

    fun tickDisconnectMillis(amountMs: Int) {
        val current = _disconnectMillisLeft.value

        if (current == -1) return

        val next = current - amountMs

        if (next <= 0) {
            _disconnectMillisLeft.value = 0
            _isDisconnecting.value = false
        } else {
            _disconnectMillisLeft.value = next
        }
    }

    // ================= OVERLAY =================

    fun setOverlayActive(context: Context, active: Boolean) {
        _isOverlayActive.value = active
        saveBool(context, KEY_OVERLAY_ACTIVE, active)
    }

    // ================= LIVE PING =================

    fun setLivePing(ping: Int) {
        _currentLivePing.value = ping
    }

    // ================= STORAGE HELPERS =================

    private fun saveSet(context: Context, key: String, set: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(key, set)
            .apply()
    }

    private fun saveInt(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(key, value)
            .apply()
    }

    private fun saveBool(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun saveString(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}