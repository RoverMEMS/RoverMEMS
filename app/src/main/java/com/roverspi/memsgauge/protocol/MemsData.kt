package com.roverspi.memsgauge.protocol

/**
 * Compact, human-meaningful snapshot merged from a 0x80 and a 0x7D frame.
 * Field set and conversions cross-checked against two references beyond
 * librosco's protocol.c: MEMSFCR's documented byte layout
 * (https://memsfcr.co.uk/ecu-data-values/) and its Android app's own
 * "Live data (text)" screen, which lists nearly every field below by name.
 * Corrections found this way, where librosco's C source disagrees:
 * - coolantTempC/ambientTempC/intakeAirTempC/fuelTempC: librosco's protocol.c
 *   uses the raw byte with no offset; MEMSFCR documents "raw - 55" as the
 *   actual Celsius conversion. Applied here, and independently confirmed by
 *   decompiling a third app (com.rovermems "MEMS Diag lite" -- its
 *   MemsDataFrame.updateFrame() does the exact same "add-int/lit8 v1, v1, -55").
 *   (ambientTempC/fuelTempC read a constant 255 on this ECU per MEMSFCR --
 *   sensors not fitted -- so treat those two as not meaningful even after
 *   conversion, and they're omitted from both reference apps' live-data views.)
 * - longTermFuelTrim: librosco passes the raw byte through; MEMSFCR documents
 *   "raw - 128" (trim centered on zero). Applied here.
 * - idleSwitch: librosco/protocol.c reads this as its own byte at frame
 *   offset 10. The decompiled "MEMS Diag lite" instead derives it from bit 4
 *   (0x10) of the offset-18 byte (the same byte as iacPosition) -- packing a
 *   status bit alongside a data byte is a common ECU pattern, and this is a
 *   second, independently-written app agreeing on that specific bit, so it's
 *   used here instead of a separate offset-10 byte.
 *
 * Still unverified against the real car -- confirm once connected (e.g.
 * coolant should read roughly ambient temp cold, ~85-95C hot). One open
 * disagreement: offset 23-24 is "coil time" here (raw * 0.002 = ms, per
 * librosco) but an unscaled "b_corr" field in MEMS Diag lite -- kept as coil
 * time since it has a specific, physically meaningful conversion factor,
 * but worth rechecking against real data.
 */
data class MemsData(
    val engineRpm: Int,
    val coolantTempC: Int,
    val ambientTempC: Int,
    val intakeAirTempC: Int,
    val fuelTempC: Int,
    val mapKpa: Int,
    val batteryVoltage: Float,
    val throttlePotVoltage: Float,
    val throttleAngleDeg: Float,
    val airFuelRatio: Float,
    val idleSwitch: Boolean,
    val parkNeutralSwitch: Boolean,
    val coolantTempSensorFault: Boolean,
    val intakeAirTempSensorFault: Boolean,
    val fuelPumpCircuitFault: Boolean,
    val throttlePotCircuitFault: Boolean,
    val iacPosition: Int,
    val idleSpeedDeviation: Int,
    val idleError: Int,
    val idleBasePos: Int,
    val ignitionAdvanceDeg: Float,
    val coilTimeMs: Float,
    val lambdaVoltageMv: Int,
    val lambdaSensorFrequency: Int,
    val lambdaSensorDutyCycle: Int,
    val lambdaSensorStatus: Boolean,
    val closedLoop: Boolean,
    val longTermFuelTrim: Int,
    val shortTermFuelTrim: Int,
    val carbonCanisterDutyCycle: Int,
    val ecuVersion: EcuVersion
) {
    companion object {
        // Per MEMSFCR's ecu-data-values page: raw byte minus this offset gives Celsius.
        private const val TEMP_OFFSET_C = 55
        // Per MEMSFCR: long term fuel trim is centered on this raw value.
        private const val FUEL_TRIM_CENTER = 128

        fun fromFrames(frame80: MemsFrame80, frame7d: MemsFrame7d, ecuVersion: EcuVersion): MemsData {
            return MemsData(
                engineRpm = (frame80.engineRpmHi shl 8) or frame80.engineRpmLo,
                coolantTempC = frame80.coolantTemp - TEMP_OFFSET_C,
                ambientTempC = frame80.ambientTemp - TEMP_OFFSET_C,
                intakeAirTempC = frame80.intakeAirTemp - TEMP_OFFSET_C,
                fuelTempC = frame80.fuelTemp - TEMP_OFFSET_C,
                mapKpa = frame80.mapKpa,
                batteryVoltage = frame80.batteryVoltageRaw / 10.0f,
                throttlePotVoltage = frame80.throttlePotRaw * 0.02f,
                throttleAngleDeg = frame7d.throttleAngleRaw * 0.6f,
                airFuelRatio = frame7d.airFuelRatioRaw / 10.0f,
                // Bit 4 of the iacPosition byte (offset 18), not the separate offset-10
                // byte librosco reads -- see the class doc comment for why.
                idleSwitch = frame80.iacPosition and 0x10 != 0,
                parkNeutralSwitch = frame80.parkNeutralSwitch != 0,
                coolantTempSensorFault = frame80.dtc0 and 0x01 != 0,
                intakeAirTempSensorFault = frame80.dtc0 and 0x02 != 0,
                fuelPumpCircuitFault = frame80.dtc1 and 0x02 != 0,
                throttlePotCircuitFault = frame80.dtc1 and 0x80 != 0,
                iacPosition = frame80.iacPosition,
                idleSpeedDeviation = (frame80.idleErrorHi shl 8) or frame80.idleErrorLo,
                idleError = frame7d.idleError2,
                idleBasePos = frame7d.idleBasePos,
                ignitionAdvanceDeg = frame80.ignitionAdvanceRaw * 0.5f - 24.0f,
                coilTimeMs = ((frame80.coilTimeHi shl 8) or frame80.coilTimeLo) * 0.002f,
                lambdaVoltageMv = frame7d.lambdaVoltageRaw * 5,
                lambdaSensorFrequency = frame7d.lambdaFreq,
                lambdaSensorDutyCycle = frame7d.lambdaDutyCycle,
                lambdaSensorStatus = frame7d.lambdaStatus != 0,
                closedLoop = frame7d.closedLoop != 0,
                longTermFuelTrim = frame7d.longTermFuelTrim - FUEL_TRIM_CENTER,
                shortTermFuelTrim = frame7d.shortTermFuelTrim,
                carbonCanisterDutyCycle = frame7d.carbonCanisterDutyCycle,
                ecuVersion = ecuVersion
            )
        }
    }
}
