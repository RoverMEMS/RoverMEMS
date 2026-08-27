package com.roverspi.memsgauge.ui.logs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.roverspi.memsgauge.logging.LogFileEntry
import com.roverspi.memsgauge.logging.LogFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogListViewModel(appContext: Context) : ViewModel() {
    private val repository = LogFileRepository(appContext)

    private val _files = MutableStateFlow<List<LogFileEntry>>(emptyList())
    val files: StateFlow<List<LogFileEntry>> = _files.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _files.value = repository.listLogFiles()
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LogListViewModel(appContext) as T
    }
}
