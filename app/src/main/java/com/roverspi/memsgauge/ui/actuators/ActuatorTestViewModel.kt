package com.roverspi.memsgauge.ui.actuators

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.protocol.MemsActuatorCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The outcome of the most recent [ActuatorTestViewModel.runCommand] call, formatted into text at the UI layer so the message picks up the current display language. */
data class ActuatorResult(val control: ActuatorControl, val success: Boolean)

class ActuatorTestViewModel(private val dataSource: EcuDataSource) : ViewModel() {

    val engineRpm: StateFlow<Int?> get() = _engineRpm
    private val _engineRpm = MutableStateFlow<Int?>(null)

    private val _lastResult = MutableStateFlow<ActuatorResult?>(null)
    val lastResult: StateFlow<ActuatorResult?> = _lastResult.asStateFlow()

    init {
        viewModelScope.launch {
            dataSource.latestData.collect { data ->
                _engineRpm.value = data?.engineRpm
            }
        }
    }

    fun runCommand(control: ActuatorControl, turnOn: Boolean) {
        val command = if (turnOn) control.onCommand else control.offCommand ?: control.onCommand
        viewModelScope.launch {
            val success = dataSource.runActuatorTest(command)
            _lastResult.value = ActuatorResult(control, success)
        }
    }

    fun clearResultMessage() {
        _lastResult.value = null
    }

    class Factory(private val dataSource: EcuDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ActuatorTestViewModel(dataSource) as T
    }
}
