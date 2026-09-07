package com.robot.simulator.bridge

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock

class GlobalLocationPublisher(context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val readyProviders = mutableSetOf<String>()
    private var lastElapsedRealtimeNanos = 0L
    private var elapsedRealtimeOffsetNanos = 0L

    fun publish(snapshot: BridgeSnapshot) {
        if (snapshot.sequence == 0L) return
        try {
            publishToProvider(LocationManager.GPS_PROVIDER, snapshot)
            publishToProvider(LocationManager.NETWORK_PROVIDER, snapshot)
        } catch (_: SecurityException) {
            readyProviders.clear()
        } catch (_: IllegalArgumentException) {
            readyProviders.clear()
        }
    }

    fun close() {
        readyProviders.toList().forEach { provider ->
            runCatching { locationManager.removeTestProvider(provider) }
        }
        readyProviders.clear()
        lastElapsedRealtimeNanos = 0L
        elapsedRealtimeOffsetNanos = 0L
    }

    private fun publishToProvider(provider: String, snapshot: BridgeSnapshot) {
        ensureProvider(provider)
        val location = Location(provider).apply {
            latitude = snapshot.latitude
            longitude = snapshot.longitude
            accuracy = snapshot.accuracyM
            speed = snapshot.speedMps
            bearing = snapshot.headingDeg
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = nextElapsedRealtimeNanos()
        }
        locationManager.setTestProviderLocation(provider, location)
    }

    private fun ensureProvider(provider: String) {
        if (provider in readyProviders) return

        val timestampFloor = listOf(
            provider,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).maxOf { source ->
            runCatching {
                locationManager.getLastKnownLocation(source)?.elapsedRealtimeNanos ?: 0L
            }.getOrDefault(0L)
        }
        lastElapsedRealtimeNanos = maxOf(lastElapsedRealtimeNanos, timestampFloor)
        val now = SystemClock.elapsedRealtimeNanos()
        if (timestampFloor >= now) {
            elapsedRealtimeOffsetNanos = maxOf(
                elapsedRealtimeOffsetNanos,
                timestampFloor - now + 1L,
            )
        }

        val properties = ProviderProperties.Builder()
            .setAccuracy(
                if (provider == LocationManager.GPS_PROVIDER) {
                    ProviderProperties.ACCURACY_FINE
                } else {
                    ProviderProperties.ACCURACY_COARSE
                },
            )
            .setPowerUsage(
                if (provider == LocationManager.GPS_PROVIDER) {
                    ProviderProperties.POWER_USAGE_HIGH
                } else {
                    ProviderProperties.POWER_USAGE_LOW
                },
            )
            .setHasAltitudeSupport(true)
            .setHasBearingSupport(true)
            .setHasSpeedSupport(true)
            .build()
        locationManager.addTestProvider(provider, properties)
        locationManager.setTestProviderEnabled(provider, true)
        readyProviders += provider
    }

    private fun nextElapsedRealtimeNanos(): Long {
        val shiftedNow = SystemClock.elapsedRealtimeNanos() + elapsedRealtimeOffsetNanos
        val next = maxOf(shiftedNow, lastElapsedRealtimeNanos + 1L)
        lastElapsedRealtimeNanos = next
        return next
    }
}
