package com.roverspi.memsgauge.ui.gauges

import androidx.annotation.StringRes
import com.roverspi.memsgauge.R

/**
 * Stable identity for one live-data metric, independent of its display
 * label -- used as the [AnalogGaugeSpec] key, the [GaugeLayoutPrefs]
 * persistence key, and the [SIMPLE_GAUGE_METRIC_INFO] lookup key, so that
 * switching the UI language never changes what a saved gauge-layout slot or
 * an info-popup lookup refers to.
 */
enum class GaugeMetric {
    RPM, MAP, TPS, COOLANT, INTAKE, BATTERY, IGNITION, LAMBDA, FUEL_TRIM
}

/**
 * A one-line explanation plus a rough "normal" range for one live-data
 * value, shown in a popup when the user taps a gauge's "？" button.
 *
 * Ranges are reference points gathered from librosco/MEMSFCR/general MEMS
 * SPi knowledge, not verified against this specific car yet -- several
 * (RPM, MAP, TPS, 吸気温, 点火進角, Lambda) genuinely depend on engine load
 * and pedal position, so their range text says "目安" (rough guide) rather
 * than claiming a strict pass/fail boundary.
 */
data class GaugeMetricInfo(
    val metric: GaugeMetric,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val normalRangeRes: Int
)

val SIMPLE_GAUGE_METRIC_INFO: Map<GaugeMetric, GaugeMetricInfo> = listOf(
    GaugeMetricInfo(GaugeMetric.RPM, R.string.metric_rpm, R.string.info_rpm_desc, R.string.info_rpm_range),
    GaugeMetricInfo(GaugeMetric.MAP, R.string.metric_map, R.string.info_map_desc, R.string.info_map_range),
    GaugeMetricInfo(GaugeMetric.TPS, R.string.metric_tps, R.string.info_tps_desc, R.string.info_tps_range),
    GaugeMetricInfo(GaugeMetric.COOLANT, R.string.metric_coolant, R.string.info_coolant_desc, R.string.info_coolant_range),
    GaugeMetricInfo(GaugeMetric.INTAKE, R.string.metric_intake, R.string.info_intake_desc, R.string.info_intake_range),
    GaugeMetricInfo(GaugeMetric.BATTERY, R.string.metric_battery, R.string.info_battery_desc, R.string.info_battery_range),
    GaugeMetricInfo(GaugeMetric.IGNITION, R.string.metric_ignition, R.string.info_ignition_desc, R.string.info_ignition_range),
    GaugeMetricInfo(GaugeMetric.LAMBDA, R.string.metric_lambda, R.string.info_lambda_desc, R.string.info_lambda_range),
    GaugeMetricInfo(GaugeMetric.FUEL_TRIM, R.string.metric_fuel_trim, R.string.info_fuel_trim_desc, R.string.info_fuel_trim_range)
).associateBy { it.metric }
