package com.roverspi.memsgauge.ui.gauges

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.datasource.ConnectionState
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.datasource.MockEcuDataSource
import com.roverspi.memsgauge.logging.DataLogger
import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GaugeViewModel(
    private val dataSource: EcuDataSource,
    private val appContext: Context
) : ViewModel() {
    val connectionState: StateFlow<ConnectionState> = dataSource.connectionState
    val ecuVersion: StateFlow<EcuVersion> = dataSource.ecuVersion
    val ecuIdRaw: StateFlow<String?> = dataSource.ecuIdRaw
    val latestData: StateFlow<MemsData?> = dataSource.latestData

    private val _clearFaultsMessage = MutableStateFlow<String?>(null)
    val clearFaultsMessage: StateFlow<String?> = _clearFaultsMessage.asStateFlow()

    private val logger = DataLogger(appContext)

    // ログは手動操作なしで自動記録する: ECUからデータが届き始めたら新しい
    // ファイルを開き、接続が切れたまま AUTO_STOP_AFTER_MS 経つと閉じる。
    // エンジン始動時の一瞬の切断(自動つなぎ直しで数秒で戻る)ではファイルを
    // 分けない。模擬データ(デモ)は記録しない。
    private val isSimulated = dataSource is MockEcuDataSource
    private var autoStopJob: Job? = null
    // Android 8-9で保存の許可が無い等で開けなかった時、サンプルごとに
    // 開き直そうとしないための印。許可が出たら onStoragePermissionGranted で解除。
    private var autoStartBlocked = false

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    private val _logFilePath = MutableStateFlow<String?>(null)
    val logFilePath: StateFlow<String?> = _logFilePath.asStateFlow()

    // Rolling window of recent samples for the グラフ (charts) view -- not
    // persisted, just enough for an on-screen trend like MEMSFCR's Charts tab.
    private val _history = MutableStateFlow<List<MemsData>>(emptyList())
    val history: StateFlow<List<MemsData>> = _history.asStateFlow()

    init {
        viewModelScope.launch {
            dataSource.latestData.collect { data ->
                if (data != null) {
                    _history.value = (_history.value + data).takeLast(MAX_HISTORY_SIZE)
                    if (!_isLogging.value && !isSimulated && !autoStartBlocked &&
                        connectionState.value == ConnectionState.CONNECTED
                    ) {
                        startLogging()
                    }
                    if (_isLogging.value) {
                        logger.logSample(data)
                    }
                }
            }
        }
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    autoStopJob?.cancel()
                    autoStopJob = null
                } else if (_isLogging.value && autoStopJob == null) {
                    autoStopJob = viewModelScope.launch {
                        delay(AUTO_STOP_AFTER_MS)
                        stopLogging()
                    }
                }
            }
        }
    }

    fun disconnect() {
        if (_isLogging.value) stopLogging()
        dataSource.disconnect()
    }

    /** Manual retry for a stalled/errored link -- see the analog screen's reconnect badge. */
    fun reconnect() {
        viewModelScope.launch { dataSource.connect() }
    }

    fun clearFaults() {
        viewModelScope.launch {
            val success = dataSource.clearFaults()
            _clearFaultsMessage.value = appContext.getString(
                if (success) R.string.clear_faults_success else R.string.clear_faults_failure
            )
        }
    }

    fun clearFaultsMessageShown() {
        _clearFaultsMessage.value = null
    }

    /** Android 8-9で保存の許可が後から出た時に、自動記録を再開できるようにする。 */
    fun onStoragePermissionGranted() {
        autoStartBlocked = false
    }

    private fun startLogging() {
        val started = logger.start()
        _isLogging.value = started
        _logFilePath.value = logger.currentLogPath
        if (!started) autoStartBlocked = true
    }

    private fun stopLogging() {
        autoStopJob?.cancel()
        autoStopJob = null
        logger.stop()
        _isLogging.value = false
    }

    override fun onCleared() {
        super.onCleared()
        if (_isLogging.value) logger.stop()
    }

    class Factory(
        private val dataSource: EcuDataSource,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GaugeViewModel(dataSource, appContext) as T
    }

    private companion object {
        const val MAX_HISTORY_SIZE = 150
        const val AUTO_STOP_AFTER_MS = 30_000L
    }
}
