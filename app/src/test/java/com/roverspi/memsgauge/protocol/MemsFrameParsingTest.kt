package com.roverspi.memsgauge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the byte-offset layout and unit conversions ported from
 * librosco (rosco.h / protocol.c) using hand-built frames with known
 * values. Runs entirely on the JVM -- no emulator or phone required, so
 * this is the first thing to check after editing anything in `protocol/`.
 */
class MemsFrameParsingTest {

    @Test
    fun ecuVersion_recognizesMems13Response() {
        val id = byteArrayOf(0x99.toByte(), 0x00, 0x03, 0x03)
        assertEquals(EcuVersion.MEMS_1_3, EcuVersion.fromD0Response(id))
    }

    @Test
    fun ecuVersion_recognizesMems16Response() {
        val id = byteArrayOf(0x99.toByte(), 0x00, 0x02, 0x03)
        assertEquals(EcuVersion.MEMS_1_6, EcuVersion.fromD0Response(id))
    }

    @Test
    fun ecuVersion_unknownForUnrecognizedResponse() {
        val id = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertEquals(EcuVersion.UNKNOWN, EcuVersion.fromD0Response(id))
    }

    // Hand-built 28-byte 0x80 frame. Field order/meaning: see MemsFrame80.kt.
    //   engineRpmHi=3, engineRpmLo=0xE8      -> rpm 1000
    //   coolantTemp=80, ambientTemp=20, intakeAirTemp=25, fuelTemp=22, mapKpa=45
    //   batteryVoltageRaw=0x8F (143)         -> 14.3 V
    //   throttlePotRaw=100                   -> 2.0 V
    //   idleSwitch=1, parkNeutralSwitch=1
    //   dtc0=0x03, dtc1=0x82                 -> all four fault bits set
    //   iacPosition=50, idleErrorHi=0, idleErrorLo=12 -> idleSpeedDeviation 12
    //   ignitionAdvanceRaw=0x80 (128)        -> 40.0 deg
    //   coilTimeHi=0, coilTimeLo=0xFA (250)  -> 0.5 ms
    private fun sampleFrame80(): MemsFrame80 = MemsFrame80.parse(
        byteArrayOf(
            28, 3, 0xE8.toByte(), 80, 20, 25, 22, 45,
            0x8F.toByte(), 100, 1, 0, 1, 0x03, 0x82.toByte(), 10,
            5, 0, 50, 0, 12, 0, 0x80.toByte(), 0,
            0xFA.toByte(), 0, 0, 0
        )
    )

    // Hand-built 32-byte 0x7D frame. Field order/meaning: see MemsFrame7d.kt.
    //   airFuelRatioRaw=0x93 (147)           -> 14.7 ratio
    //   lambdaVoltageRaw=40                  -> 200 mV
    //   lambdaFreq=77, lambdaDutyCycle=88, lambdaStatus=1 (true)
    //   closedLoop=1
    //   longTermFuelTrim=5, shortTermFuelTrim=0xC8 (200) (kept as two distinct fields)
    //   carbonCanisterDutyCycle=15
    //   idleBasePos=30
    //   idleError2=99
    private fun sampleFrame7d(): MemsFrame7d = MemsFrame7d.parse(
        byteArrayOf(
            32, 1, 50, 0, 0x93.toByte(), 0, 40, 77,
            88, 1, 1, 5, 0xC8.toByte(), 15, 0, 30,
            0, 0, 0, 0, 99, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
        )
    )

    private fun sampleData(ecuVersion: EcuVersion = EcuVersion.MEMS_1_3): MemsData =
        MemsData.fromFrames(sampleFrame80(), sampleFrame7d(), ecuVersion)

    @Test
    fun frame80_hasCorrectByteCount() {
        assertEquals(28, MemsFrame80.FRAME_SIZE)
    }

    @Test
    fun frame7d_hasCorrectByteCount() {
        assertEquals(32, MemsFrame7d.FRAME_SIZE)
    }

    @Test
    fun memsData_combinesEngineRpmFromHiLoBytes() {
        assertEquals(1000, sampleData().engineRpm)
    }

    @Test
    fun memsData_convertsBatteryVoltage() {
        assertEquals(14.3f, sampleData().batteryVoltage, 0.001f)
    }

    @Test
    fun memsData_convertsThrottlePotVoltage() {
        assertEquals(2.0f, sampleData().throttlePotVoltage, 0.001f)
    }

    @Test
    fun memsData_convertsIgnitionAdvance() {
        assertEquals(40.0f, sampleData().ignitionAdvanceDeg, 0.001f)
    }

    @Test
    fun memsData_convertsCoilTime() {
        assertEquals(0.5f, sampleData().coilTimeMs, 0.001f)
    }

    @Test
    fun memsData_convertsLambdaVoltage() {
        assertEquals(200, sampleData().lambdaVoltageMv)
    }

    @Test
    fun memsData_keepsLongAndShortFuelTrimSeparate() {
        // longTermFuelTrim is centered on raw 128 per MEMSFCR (raw 5 -> -123);
        // shortTermFuelTrim has no documented offset, so it stays raw (200).
        val data = sampleData()
        assertEquals(-123, data.longTermFuelTrim)
        assertEquals(200, data.shortTermFuelTrim)
    }

    @Test
    fun memsData_convertsCoolantTempWithOffset() {
        // raw 80 - 55 = 25 C, per MEMSFCR's ecu-data-values page.
        assertEquals(25, sampleData().coolantTempC)
    }

    @Test
    fun memsData_convertsThrottleAngle() {
        // raw 50 * 0.6 = 30.0 degrees.
        assertEquals(30.0f, sampleData().throttleAngleDeg, 0.001f)
    }

    @Test
    fun memsData_convertsAirFuelRatio() {
        // raw 0x93 (147) / 10.0 = 14.7.
        assertEquals(14.7f, sampleData().airFuelRatio, 0.001f)
    }

    @Test
    fun memsData_decodesIndividualFaultFlags() {
        // dtc0 = 0x03 -> coolant + intake air sensor faults (bits 0,1)
        // dtc1 = 0x82 -> fuel pump + throttle pot circuit faults (bits 1,7)
        val data = sampleData()
        assertTrue(data.coolantTempSensorFault)
        assertTrue(data.intakeAirTempSensorFault)
        assertTrue(data.fuelPumpCircuitFault)
        assertTrue(data.throttlePotCircuitFault)
    }

    @Test
    fun memsData_readsLambdaSensorDiagnostics() {
        val data = sampleData()
        assertEquals(77, data.lambdaSensorFrequency)
        assertEquals(88, data.lambdaSensorDutyCycle)
        assertTrue(data.lambdaSensorStatus)
    }

    @Test
    fun memsData_readsCarbonCanisterDutyCycle() {
        assertEquals(15, sampleData().carbonCanisterDutyCycle)
    }

    @Test
    fun memsData_readsIdleSpeedDeviationAndIdleError() {
        // idleSpeedDeviation comes from the 0x80 frame's idle_error_hi/lo (here: 12).
        // idleError comes from the 0x7D frame's separate idle_error_2 byte (here: 99).
        val data = sampleData()
        assertEquals(12, data.idleSpeedDeviation)
        assertEquals(99, data.idleError)
    }

    @Test
    fun memsData_decodesBooleanSwitches() {
        val data = sampleData()
        assertTrue(data.idleSwitch)
        assertTrue(data.parkNeutralSwitch)
        assertTrue(data.closedLoop)
    }

    @Test
    fun memsData_carriesEcuVersionThrough() {
        assertEquals(EcuVersion.MEMS_1_6, sampleData(EcuVersion.MEMS_1_6).ecuVersion)
    }
}
