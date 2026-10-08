package com.roverspi.memsgauge.ble

import android.content.Context

/**
 * 前回つながったBLE機器のアドレスと「起動時に自動接続するか」の設定。
 * 自動接続はアプリを起動して最初の接続画面でだけ試す(メニューから手動で
 * 接続画面に戻った時に勝手につなぎ直さない)。
 */
object LastBleDevicePrefs {
    private const val PREFS_NAME = "last_ble_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_AUTO = "auto_connect"

    /** このプロセスで自動接続を試したか。アプリを起動し直すと false に戻る。 */
    @Volatile
    var autoConnectTried = false

    fun lastAddress(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun save(context: Context, address: String) {
        prefs(context).edit().putString(KEY_ADDRESS, address).apply()
    }

    fun isAutoConnectEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
