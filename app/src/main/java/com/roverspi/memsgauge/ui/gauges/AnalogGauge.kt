package com.roverspi.memsgauge.ui.gauges

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.roverspi.memsgauge.R

// Dial sweeps 240° clockwise starting from the lower-left (like a classic
// car speedometer/tachometer), so min sits at 7-8 o'clock and max at 4-5
// o'clock with the top of the dial in between. Must match the tick/numeral
// layout baked into the gauge_*.png face images.
private const val START_ANGLE_DEG = 150f
private const val SWEEP_ANGLE_DEG = 240f

// gauge_needle.png's pivot hub isn't drawn at the image's exact center --
// measured by pixel-scanning the actual file (tailless needle, regenerated
// with a thinner-bezel face set): horizontally centered (x=511/1024) but
// the hub sits at y=668/1024 (~65% down). These fractions are that hub's
// position, used to shift the image so the hub lands on the dial's true
// center and to pin scale/rotation to that same point instead of the
// image's geometric center.
private const val NEEDLE_HUB_FRACTION_X = 0.5f
private const val NEEDLE_HUB_FRACTION_Y = 0.652f

// Hub-to-tip distance in the source art is ~382px of a 1024px-wide image
// (~37.3% of the image width). Measured the new (thin-bezel) face images'
// major tick marks reaching out to ~46.8% of the image width, so the
// needle is scaled up slightly to reach ~42%, just inside the tick marks.
private const val NEEDLE_LENGTH_SCALE = 1.12f

/**
 * One anchor point for a non-linear ("expanded") gauge scale: [value] maps
 * to [angleFraction] (0f = start of the sweep, 1f = end). A gauge's default
 * scale is just two points (min→0f, max→1f), which is a plain linear scale.
 * Passing extra points in between lets one part of the range (e.g. a
 * frequently-watched "hot" zone) claim more of the dial's arc per unit than
 * the rest, matching how the water temp face image was drawn.
 */
data class GaugeScalePoint(val value: Float, val angleFraction: Float)

/**
 * A needle-style analog gauge built from two layered images: a static face
 * ([faceRes], a `gauge_*.png` drawable with numerals/ticks/bezel already
 * baked in) and a shared needle image ([R.drawable.gauge_needle]) rotated
 * on top to point at [value]. The needle artwork is drawn pointing straight
 * up with its pivot at the image's exact center, so a plain [graphicsLayer]
 * rotation is enough -- no runtime vector drawing needed.
 */
@Composable
fun AnalogGauge(
    faceRes: Int,
    value: Float,
    minValue: Float,
    maxValue: Float,
    sizeDp: Dp,
    modifier: Modifier = Modifier,
    scalePoints: List<GaugeScalePoint> = listOf(
        GaugeScalePoint(minValue, 0f),
        GaugeScalePoint(maxValue, 1f)
    )
) {
    // Piecewise-linear value→angle mapping: walks scalePoints (sorted by
    // value) to find which segment v falls in, then interpolates within
    // just that segment. With the default 2-point list this is a plain
    // linear scale; extra points create a non-linear/expanded scale.
    fun valueToAngleDeg(v: Float): Float {
        val points = scalePoints
        val clampedV = v.coerceIn(points.first().value, points.last().value)
        var i = 0
        while (i < points.size - 2 && clampedV > points[i + 1].value) i++
        val p0 = points[i]
        val p1 = points[i + 1]
        val segmentRange = (p1.value - p0.value).takeIf { it > 0.0001f } ?: 1f
        val segmentFraction = ((clampedV - p0.value) / segmentRange).coerceIn(0f, 1f)
        val fraction = p0.angleFraction + segmentFraction * (p1.angleFraction - p0.angleFraction)
        return START_ANGLE_DEG + fraction * SWEEP_ANGLE_DEG
    }

    // The needle image points at our angle convention's 270° (straight up)
    // when unrotated. graphicsLayer's rotationZ is clockwise, same sense as
    // our angle sweep, so the needed rotation is simply the offset between
    // the two: target angle minus the needle's native 270° pose.
    val needleRotationDeg = valueToAngleDeg(value) - 270f

    Box(modifier = modifier.size(sizeDp)) {
        Image(
            painter = painterResource(faceRes),
            contentDescription = null,
            modifier = Modifier.size(sizeDp),
            contentScale = ContentScale.Fit
        )
        Image(
            painter = painterResource(R.drawable.gauge_needle),
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                // Shift so the hub (not the image's geometric center) lands
                // on the dial's center.
                .offset(
                    x = sizeDp * (0.5f - NEEDLE_HUB_FRACTION_X),
                    y = sizeDp * (0.5f - NEEDLE_HUB_FRACTION_Y)
                )
                .graphicsLayer {
                    scaleX = NEEDLE_LENGTH_SCALE
                    scaleY = NEEDLE_LENGTH_SCALE
                    rotationZ = needleRotationDeg
                    // Pivot scale+rotation on the hub's position within the
                    // image itself (fraction of its own bounds), which after
                    // the offset above coincides with the dial's center.
                    transformOrigin = TransformOrigin(NEEDLE_HUB_FRACTION_X, NEEDLE_HUB_FRACTION_Y)
                },
            contentScale = ContentScale.Fit
        )
    }
}
