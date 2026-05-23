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

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive = _isVpnActive.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages = _selectedPackages.asStateFlow()

    private val _fakePingValue = MutableStateFlow(120) // default 120ms
    val fakePingValue = _fakePingValue.asStateFlow()

    private val _isFakePingEnabled = MutableStateFlow(false)
    val isFakePingEnabled = _isFakePingEnabled.asStateFlow()

    private val _lagMode = MutableStateFlow("Both") // "Upload Only", "Download Only", "Both"
    val lagMode = _lagMode.asStateFlow()

    private val _cutSeconds = MutableStateFlow(5) // Default 5s
    val cutSeconds = _cutSeconds.asStateFlow()

    private val _cutMillis = MutableStateFlow(0) // Default 0ms
    val cutMillis = _cutMillis.asStateFlow()

    private val _isInfinite = MutableStateFlow(false) // Default false
    val isInfinite = _isInfinite.asStateFlow()

    private val _isDisconnecting = MutableStateFlow(false)
    val isDisconnecting = _isDisconnecting.asStateFlow()

    private val _disconnectMillisLeft = MutableStateFlow(0)
    val disconnectMillisLeft = _disconnectMillisLeft.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive = _isOverlayActive.asStateFlow()

    private val _currentLivePing = MutableStateFlow(0)
    val currentLivePing = _currentLivePing.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _selectedPackages.value = prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet()) ?: emptySet()
        _fakePingValue.value = prefs.getInt(KEY_FAKE_PING_VAL, 120)
        _isFakePingEnabled.value = prefs.getBoolean(KEY_FAKE_PING_ENABLED, false)
        _lagMode.value = prefs.getString(KEY_LAG_MODE, "Both") ?: "Both"
        _cutSeconds.value = prefs.getInt(KEY_CUT_SECONDS, 5)
        _cutMillis.value = prefs.getInt(KEY_CUT_MILLIS, 0)
        _isInfinite.value = prefs.getBoolean(KEY_IS_INFINITE, false)
        _isOverlayActive.value = prefs.getBoolean(KEY_OVERLAY_ACTIVE, false)
    }

    fun setVpnActive(active: Boolean) {
        _isVpnActive.value = active
    }

    fun toggleSelectedPackage(context: Context, packageName: String) {
        val current = _selectedPackages.value.toMutableSet()
        if (packageName in current) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        _selectedPackages.value = current
        saveSelectedPackages(context, current)
    }

    fun selectAllPackages(context: Context, packageNames: List<String>) {
        val newSet = packageNames.toSet()
        _selectedPackages.value = newSet
        saveSelectedPackages(context, newSet)
    }

    fun clearAllPackages(context: Context) {
        _selectedPackages.value = emptySet()
        saveSelectedPackages(context, emptySet())
    }

    private fun saveSelectedPackages(context: Context, set: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SELECTED_PACKAGES, set).apply()
    }

    fun setFakePingValue(context: Context, valMs: Int) {
        _fakePingValue.value = valMs
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_FAKE_PING_VAL, valMs).apply()
    }

    fun setFakePingEnabled(context: Context, enabled: Boolean) {
        _isFakePingEnabled.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FAKE_PING_ENABLED, enabled).apply()
    }

    fun setLagMode(context: Context, mode: String) {
        _lagMode.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAG_MODE, mode).apply()
    }

    fun setCutSeconds(context: Context, secs: Int) {
        _cutSeconds.value = secs
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CUT_SECONDS, secs).apply()
    }

    fun setCutMillis(context: Context, ms: Int) {
        _cutMillis.value = ms
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CUT_MILLIS, ms).apply()
    }

    fun setIsInfinite(context: Context, infinite: Boolean) {
        _isInfinite.value = infinite
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_INFINITE, infinite).apply()
    }

    fun setDisconnecting(active: Boolean, millis: Int = 0) {
        _isDisconnecting.value = active
        _disconnectMillisLeft.value = millis
    }

    fun tickDisconnectMillis(amountMs: Int) {
        if (_disconnectMillisLeft.value == -1) {
            // Infinite mode: never decrement automatically
            return
        }
        if (_disconnectMillisLeft.value > amountMs) {
            _disconnectMillisLeft.value -= amountMs
        } else {
            _disconnectMillisLeft.value = 0
            _isDisconnecting.value = false
        }
    }

    fun setOverlayActive(context: Context, active: Boolean) {
        _isOverlayActive.value = active
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OVERLAY_ACTIVE, active).apply()
    }

    fun setLivePing(ping: Int) {
        _currentLivePing.value = ping
    }
}
