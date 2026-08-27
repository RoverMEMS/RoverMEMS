package com.roverspi.memsgauge.ui.actuators

import androidx.annotation.StringRes
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.protocol.MemsActuatorCommand

/**
 * One row on the "Test Components" screen: a labeled actuator with an ON
 * command and an optional OFF command. [offCommand] is null for one-shot
 * tests (injector, coil) that don't have a distinct off state.
 */
data class ActuatorControl(
    @StringRes val labelRes: Int,
    val onCommand: MemsActuatorCommand,
    val offCommand: MemsActuatorCommand?
)

/**
 * Mirrors MEMSFCR's "Test Components" screen. "Temperature Gauge" from that
 * screen is deliberately omitted -- its command byte isn't confirmed in
 * librosco or any other source checked so far, and guessing one risks
 * sending an unknown command to the real ECU.
 */
val ACTUATOR_CONTROLS = listOf(
    ActuatorControl(R.string.actuator_fuel_pump, MemsActuatorCommand.FUEL_PUMP_ON, MemsActuatorCommand.FUEL_PUMP_OFF),
    ActuatorControl(R.string.actuator_manifold_heater, MemsActuatorCommand.PTC_RELAY_ON, MemsActuatorCommand.PTC_RELAY_OFF),
    ActuatorControl(R.string.actuator_ac, MemsActuatorCommand.AC_RELAY_ON, MemsActuatorCommand.AC_RELAY_OFF),
    ActuatorControl(R.string.actuator_purge_valve, MemsActuatorCommand.PURGE_VALVE_ON, MemsActuatorCommand.PURGE_VALVE_OFF),
    ActuatorControl(R.string.actuator_lambda_heater, MemsActuatorCommand.O2_HEATER_ON, MemsActuatorCommand.O2_HEATER_OFF),
    ActuatorControl(R.string.actuator_fan1, MemsActuatorCommand.FAN1_ON, MemsActuatorCommand.FAN1_OFF),
    ActuatorControl(R.string.actuator_fan2, MemsActuatorCommand.FAN2_ON, MemsActuatorCommand.FAN2_OFF),
    ActuatorControl(R.string.actuator_injector, MemsActuatorCommand.TEST_INJECTORS, null),
    ActuatorControl(R.string.actuator_ignition_coil, MemsActuatorCommand.FIRE_COIL, null)
)
