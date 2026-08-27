package com.roverspi.memsgauge.protocol

/**
 * Commands used to request data frames and clear fault codes.
 * Values ported from librosco's rosco.h (mems_data_command).
 */
enum class MemsDataCommand(val byte: Int) {
    REQ_DATA_7D(0x7D),
    REQ_DATA_80(0x80),
    CLEAR_FAULTS(0xCC),
    HEARTBEAT(0xF4),
    GET_IAC_POSITION(0xFB)
}

/**
 * Commands used to test actuators on the car. Ported from librosco's
 * rosco.h (mems_actuator_command). The purge valve/O2 heater/fan commands
 * were present in rosco.h but excluded from librosco's own build ("I
 * currently have no way to test these commands" per its source comment);
 * they're included here because MEMSFCR's "Test Components" screen
 * successfully exposes the same set (as Purge Valve / Lambda Heater / Fan 1
 * / Fan 2), which corroborates the byte values.
 */
enum class MemsActuatorCommand(val byte: Int) {
    FUEL_PUMP_ON(0x11),
    FUEL_PUMP_OFF(0x01),
    PTC_RELAY_ON(0x12),
    PTC_RELAY_OFF(0x02),
    AC_RELAY_ON(0x13),
    AC_RELAY_OFF(0x03),
    PURGE_VALVE_ON(0x18),
    PURGE_VALVE_OFF(0x08),
    O2_HEATER_ON(0x19),
    O2_HEATER_OFF(0x09),
    FAN1_ON(0x1D),
    FAN1_OFF(0x0D),
    FAN2_ON(0x1E),
    FAN2_OFF(0x0E),
    TEST_INJECTORS(0xF7),
    FIRE_COIL(0xF8),
    OPEN_IAC(0xFD),
    CLOSE_IAC(0xFE)
}
