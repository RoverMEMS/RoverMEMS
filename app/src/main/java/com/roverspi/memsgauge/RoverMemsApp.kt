package com.roverspi.memsgauge

import android.app.Application
import android.content.Context
import com.roverspi.memsgauge.datasource.BleEcuDataSource
import com.roverspi.memsgauge.datasource.MockEcuDataSource
import com.roverspi.memsgauge.datasource.UsbEcuDataSource

/**
 * Holds the app's [com.roverspi.memsgauge.datasource.EcuDataSource]
 * instances for the lifetime of the process. No DI framework -- the app is
 * small enough that a couple of lazily-created singletons here are simpler
 * to read and maintain than wiring up Hilt/Koin.
 */
class RoverMemsApp : Application() {

    val mockDataSource: MockEcuDataSource by lazy { MockEcuDataSource() }
    val bleDataSource: BleEcuDataSource by lazy { BleEcuDataSource(applicationContext) }
    val usbDataSource: UsbEcuDataSource by lazy { UsbEcuDataSource(applicationContext) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(base))
    }
}
