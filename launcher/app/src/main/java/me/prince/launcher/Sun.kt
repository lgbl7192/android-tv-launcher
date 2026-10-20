package me.prince.launcher

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

private val FALLBACK_SUNRISE = LocalTime.of(7, 0)
private val FALLBACK_SUNSET = LocalTime.of(19, 0)
// Zenith angle at sunrise and sunset, 90° plus refraction and the sun's radius
private const val HORIZON_DEGREES = 90.833
private const val UNIX_EPOCH_JULIAN_DAY = 2440587.5

sealed interface Daylight {
    data class Between(val sunrise: ZonedDateTime, val sunset: ZonedDateTime) : Daylight
    data object MidnightSun : Daylight
    data object PolarNight : Daylight

    fun isLightAt(time: ZonedDateTime): Boolean = when (this) {
        is Between -> !time.isBefore(sunrise) && time.isBefore(sunset)
        MidnightSun -> true
        PolarNight -> false
    }
}

fun isLight(appearance: Appearance, location: LatLon?, now: ZonedDateTime): Boolean = when (appearance) {
    Appearance.Light -> true
    Appearance.Dark -> false
    Appearance.Auto -> daylight(location, now.toLocalDate(), now.zone).isLightAt(now)
}

// NOAA's solar calculator (gml.noaa.gov/grad/solcalc)
fun daylight(location: LatLon?, date: LocalDate, zone: ZoneId): Daylight {
    if (location == null) {
        return Daylight.Between(date.atTime(FALLBACK_SUNRISE).atZone(zone), date.atTime(FALLBACK_SUNSET).atZone(zone))
    }
    val (latitude, longitude) = location
    // The UTC day holding local noon, so zones far from their longitude (UTC+14) still get this date's sun
    val midnight = date.atTime(12, 0).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant()
    val julianMidnight = midnight.epochSecond / 86_400.0 + UNIX_EPOCH_JULIAN_DAY

    // Minutes after UTC midnight
    fun sunAt(minutes: Double) = solarPosition(julianMidnight + minutes / 1440)
    fun noonMinutes(sun: SolarPosition) = 720 - 4 * longitude - sun.equationOfTime

    val atNoon = sunAt(720 - 4 * longitude)
    val noonCosine = hourAngleCosine(latitude, atNoon.declination)
    if (noonCosine < -1) return Daylight.MidnightSun
    if (noonCosine > 1) return Daylight.PolarNight
    val noon = noonMinutes(atNoon)
    val halfDay = 4 * Math.toDegrees(acos(noonCosine))

    // NOAA's second pass: the sun's position at the first estimate
    fun refine(estimate: Double, sign: Int): Double {
        val sun = sunAt(estimate)
        val cosine = hourAngleCosine(latitude, sun.declination).takeIf { it in -1.0..1.0 } ?: return estimate
        return noonMinutes(sun) + sign * 4 * Math.toDegrees(acos(cosine))
    }
    fun at(minutes: Double): ZonedDateTime = midnight.plusSeconds((minutes * 60).roundToLong()).atZone(zone)
    return Daylight.Between(at(refine(noon - halfDay, -1)), at(refine(noon + halfDay, 1)))
}

private fun hourAngleCosine(latitude: Double, declination: Double): Double {
    val lat = Math.toRadians(latitude)
    val dec = Math.toRadians(declination)
    return cos(Math.toRadians(HORIZON_DEGREES)) / (cos(lat) * cos(dec)) - tan(lat) * tan(dec)
}

private class SolarPosition(val declination: Double, val equationOfTime: Double)

private fun solarPosition(julianDay: Double): SolarPosition {
    val t = (julianDay - 2451545.0) / 36525.0
    val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
    val meanAnomaly = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t))
    val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    val centre = sin(meanAnomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
        sin(2 * meanAnomaly) * (0.019993 - 0.000101 * t) +
        sin(3 * meanAnomaly) * 0.000289
    val omega = Math.toRadians(125.04 - 1934.136 * t)
    val apparentLongitude = Math.toRadians(meanLongitude + centre - 0.00569 - 0.00478 * sin(omega))
    val meanObliquity = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
    val obliquity = Math.toRadians(meanObliquity + 0.00256 * cos(omega))
    val y = tan(obliquity / 2).pow(2)
    val l0 = Math.toRadians(meanLongitude)
    val equationOfTime = 4 * Math.toDegrees(
        y * sin(2 * l0) -
            2 * eccentricity * sin(meanAnomaly) +
            4 * eccentricity * y * sin(meanAnomaly) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) -
            1.25 * eccentricity * eccentricity * sin(2 * meanAnomaly)
    )
    return SolarPosition(Math.toDegrees(asin(sin(obliquity) * sin(apparentLongitude))), equationOfTime)
}
