package com.robot.simulator.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(start: PointM, end: PointM): Double {
        val latitudeDelta = (end.latitude - start.latitude).toRadians()
        val longitudeDelta = (end.longitude - start.longitude).toRadians()
        val startLatitude = start.latitude.toRadians()
        val endLatitude = end.latitude.toRadians()
        val haversine = sin(latitudeDelta / 2).let { it * it } +
            cos(startLatitude) * cos(endLatitude) *
            sin(longitudeDelta / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    fun withCumulativeDistances(points: List<PointM>): List<PointM> {
        var cumulative = 0.0
        return points.mapIndexed { index, point ->
            if (index > 0) cumulative += distanceM(points[index - 1], point)
            point.copy(cumulativeDistanceM = cumulative)
        }
    }

    fun interpolate(start: PointM, end: PointM, fraction: Double): PointM {
        val value = fraction.coerceIn(0.0, 1.0)
        return PointM(
            latitude = start.latitude + (end.latitude - start.latitude) * value,
            longitude = start.longitude + (end.longitude - start.longitude) * value,
        )
    }

    fun bearingDeg(start: PointM, end: PointM): Double {
        val startLatitude = start.latitude.toRadians()
        val endLatitude = end.latitude.toRadians()
        val longitudeDelta = (end.longitude - start.longitude).toRadians()
        val y = sin(longitudeDelta) * cos(endLatitude)
        val x = cos(startLatitude) * sin(endLatitude) -
            sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta)
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

object RoutePointCodec {
    fun encode(points: List<PointM>): String = points.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ",",
    ) { point ->
        "[${point.latitude},${point.longitude},${point.cumulativeDistanceM}]"
    }

    fun decode(payload: String): List<PointM> {
        val content = payload.trim().removePrefix("[").removeSuffix("]").trim()
        if (content.isEmpty()) return emptyList()
        return content.split("],[").map { encodedPoint ->
            val values = encodedPoint.removePrefix("[").removeSuffix("]").split(',')
            require(values.size == 3) { "Each route point must contain latitude, longitude and distance" }
            PointM(
                latitude = values[0].toDouble(),
                longitude = values[1].toDouble(),
                cumulativeDistanceM = values[2].toDouble(),
            )
        }
    }
}
