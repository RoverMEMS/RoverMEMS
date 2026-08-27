package com.roverspi.memsgauge.ble

import java.util.UUID

/**
 * A GATT profile used by cheap BLE-UART bridge modules. The exact module
 * (HM-10, HC-08, or a Nordic-UART-Service clone) hasn't been purchased yet,
 * so both common patterns are defined here and [BleUartTransport] tries each
 * in turn against whatever services the connected module actually reports.
 */
data class BleUartProfile(
    val displayName: String,
    val serviceUuid: UUID,
    /** Characteristic the phone writes commands to. */
    val txCharUuid: UUID,
    /** Characteristic the phone subscribes to for notifications from the ECU. */
    val rxCharUuid: UUID
)

object BleUartProfiles {
    /** Classic HM-10 / HC-08 / JDY-08 clones: one characteristic used for both directions. */
    val HM10 = BleUartProfile(
        displayName = "HM-10 / HC-08 (FFE0)",
        serviceUuid = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
        txCharUuid = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
        rxCharUuid = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
    )

    /** Nordic UART Service (NUS), used by many other BLE-UART bridge clones. */
    val NORDIC_UART = BleUartProfile(
        displayName = "Nordic UART Service",
        serviceUuid = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
        txCharUuid = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        rxCharUuid = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    )

    val KNOWN_PROFILES: List<BleUartProfile> = listOf(HM10, NORDIC_UART)
}
