package com.wearos.ancsbridge.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Receives background health data from Wear OS Health Services
 * (steps, distance, floors, heart rate, sleep state) and buffers it in Room.
 */
class PassiveHealthService : PassiveListenerService() {

    companion object {
        private const val TAG = "PassiveHealthService"
        private const val PREFS = "health_passive"
        private const val KEY_SLEEP_START = "sleep_start_ms"
        private const val MIN_SLEEP_MS = 15 * 60_000L

        /**
         * Register for passive data. Safe to call repeatedly — Health Services
         * replaces the previous registration. Registrations don't survive reboot,
         * so AncsService calls this on every start.
         */
        suspend fun register(context: Context) {
            val hasActivity = granted(context, Manifest.permission.ACTIVITY_RECOGNITION)
            val hasSensors = granted(context, Manifest.permission.BODY_SENSORS)
            if (!hasActivity && !hasSensors) {
                Log.i(TAG, "No health permissions granted — passive collection disabled")
                return
            }

            try {
                val client = HealthServices.getClient(context).passiveMonitoringClient
                val capabilities = client.getCapabilitiesAsync().await()
                val supported = capabilities.supportedDataTypesPassiveMonitoring

                val wanted = buildSet {
                    if (hasActivity) {
                        add(DataType.STEPS)
                        add(DataType.DISTANCE)
                        add(DataType.FLOORS)
                    }
                    if (hasSensors) add(DataType.HEART_RATE_BPM)
                }.filter { it in supported }.toSet()

                val sleepSupported = hasActivity &&
                    UserActivityState.USER_ACTIVITY_ASLEEP in capabilities.supportedUserActivityStates

                if (wanted.isEmpty() && !sleepSupported) {
                    Log.w(TAG, "Watch supports none of the requested passive data types")
                    return
                }

                val config = PassiveListenerConfig.builder()
                    .setDataTypes(wanted)
                    .setShouldUserActivityInfoBeRequested(sleepSupported)
                    .build()
                client.setPassiveListenerServiceAsync(PassiveHealthService::class.java, config).await()
                Log.i(TAG, "Passive listener registered: $wanted sleep=$sleepSupported")
            } catch (e: Exception) {
                Log.e(TAG, "Passive registration failed", e)
            }
        }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /** Wall-clock instant of device boot, used to convert Health Services timestamps. */
        fun bootInstant(): Instant =
            Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = bootInstant()
        val records = mutableListOf<HealthRecord>()

        dataPoints.getData(DataType.STEPS).forEach { p ->
            records += intervalRecord(HealthProtocol.TYPE_STEPS, p.getStartInstant(boot), p.getEndInstant(boot), p.value.toDouble())
        }
        dataPoints.getData(DataType.DISTANCE).forEach { p ->
            records += intervalRecord(HealthProtocol.TYPE_DISTANCE, p.getStartInstant(boot), p.getEndInstant(boot), p.value)
        }
        dataPoints.getData(DataType.FLOORS).forEach { p ->
            records += intervalRecord(HealthProtocol.TYPE_FLOORS, p.getStartInstant(boot), p.getEndInstant(boot), p.value)
        }
        dataPoints.getData(DataType.HEART_RATE_BPM).forEach { p ->
            records += HealthRecord(
                type = HealthProtocol.TYPE_HEART_RATE,
                startMs = p.getTimeInstant(boot).toEpochMilli(),
                durationMs = 0,
                value = p.value
            )
        }

        val valid = records.filter { it.value > 0 }
        if (valid.isEmpty()) return
        Log.d(TAG, "Buffering ${valid.size} passive records")
        runBlocking(Dispatchers.IO) {
            HealthDatabase.get(this@PassiveHealthService).recordDao().insertAll(valid)
        }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val changedAt = info.stateChangeTime.toEpochMilli()
        val sleepStart = prefs.getLong(KEY_SLEEP_START, 0L)

        if (info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP) {
            if (sleepStart == 0L) prefs.edit().putLong(KEY_SLEEP_START, changedAt).apply()
            return
        }

        if (sleepStart == 0L) return
        prefs.edit().remove(KEY_SLEEP_START).apply()
        val duration = changedAt - sleepStart
        if (duration < MIN_SLEEP_MS) return

        Log.i(TAG, "Sleep session ended: ${duration / 60_000} min")
        runBlocking(Dispatchers.IO) {
            HealthDatabase.get(this@PassiveHealthService).recordDao().insertAll(
                listOf(
                    HealthRecord(
                        type = HealthProtocol.TYPE_SLEEP,
                        startMs = sleepStart,
                        durationMs = duration,
                        value = 1.0
                    )
                )
            )
        }
    }

    private fun intervalRecord(type: Int, start: Instant, end: Instant, value: Double) =
        HealthRecord(
            type = type,
            startMs = start.toEpochMilli(),
            durationMs = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(0),
            value = value
        )
}
