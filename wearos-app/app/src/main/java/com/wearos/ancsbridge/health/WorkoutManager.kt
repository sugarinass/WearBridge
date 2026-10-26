package com.wearos.ancsbridge.health

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Records a workout with Health Services' ExerciseClient and buffers the
 * summary plus heart-rate samples for sync to Apple Health.
 *
 * Samsung Health's own workouts aren't readable by third-party apps, so
 * workouts must be recorded here to reach HealthKit.
 */
object WorkoutManager {

    private const val TAG = "WorkoutManager"
    private const val MIN_HR_SAMPLE_INTERVAL_MS = 5_000L

    data class WorkoutState(
        val active: Boolean = false,
        val activity: Int = HealthProtocol.ACTIVITY_OTHER,
        val startMs: Long = 0,
        val heartRate: Double? = null,
        val calories: Double = 0.0,
        val distanceMeters: Double = 0.0
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val heartRateSamples = mutableListOf<HealthRecord>()
    private var appContext: Context? = null

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val boot = PassiveHealthService.bootInstant()
            val metrics = update.latestMetrics

            metrics.getData(DataType.HEART_RATE_BPM).forEach { p ->
                val t = p.getTimeInstant(boot).toEpochMilli()
                val last = heartRateSamples.lastOrNull()?.startMs ?: 0L
                if (p.value > 0 && t - last >= MIN_HR_SAMPLE_INTERVAL_MS) {
                    heartRateSamples += HealthRecord(
                        type = HealthProtocol.TYPE_HEART_RATE,
                        startMs = t,
                        durationMs = 0,
                        value = p.value
                    )
                }
            }

            val current = _state.value
            _state.value = current.copy(
                heartRate = metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: current.heartRate,
                calories = metrics.getData(DataType.CALORIES_TOTAL)?.total ?: current.calories,
                distanceMeters = metrics.getData(DataType.DISTANCE_TOTAL)?.total ?: current.distanceMeters
            )

            if (update.exerciseStateInfo.state.isEnded) finish()
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise callback registration failed", throwable)
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    /** Returns null on success, or a short error message. */
    suspend fun start(context: Context, activity: Int): String? {
        if (_state.value.active) return null
        val ctx = context.applicationContext
        appContext = ctx

        return try {
            val client = HealthServices.getClient(ctx).exerciseClient
            val capabilities = client.getCapabilitiesAsync().await()
            val exerciseType = exerciseTypeFor(activity)
                .takeIf { it in capabilities.supportedExerciseTypes }
                ?: ExerciseType.WORKOUT
            val supported = capabilities.getExerciseTypeCapabilities(exerciseType).supportedDataTypes
            val dataTypes = setOf<DataType<*, *>>(
                DataType.HEART_RATE_BPM,
                DataType.CALORIES_TOTAL,
                DataType.DISTANCE_TOTAL
            ).filter { it in supported }.toSet()

            val config = ExerciseConfig.builder(exerciseType)
                .setDataTypes(dataTypes)
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
                .build()

            heartRateSamples.clear()
            client.setUpdateCallback(callback)
            client.startExerciseAsync(config).await()
            _state.value = WorkoutState(
                active = true,
                activity = activity,
                startMs = System.currentTimeMillis()
            )
            ctx.startForegroundService(Intent(ctx, WorkoutService::class.java))
            Log.i(TAG, "Workout started: $exerciseType with $dataTypes")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start workout", e)
            e.message ?: "Failed to start"
        }
    }

    suspend fun stop() {
        val ctx = appContext ?: return
        if (!_state.value.active) return
        try {
            HealthServices.getClient(ctx).exerciseClient.endExerciseAsync().await()
        } catch (e: Exception) {
            Log.e(TAG, "endExercise failed", e)
        }
        // The ENDED update usually follows, but don't depend on it.
        finish()
    }

    private fun finish() {
        val ctx = appContext ?: return
        val s = _state.value
        if (!s.active) return
        _state.value = s.copy(active = false)

        val endMs = System.currentTimeMillis()
        val records = heartRateSamples.toList() + HealthRecord(
            type = HealthProtocol.TYPE_WORKOUT,
            subtype = s.activity,
            startMs = s.startMs,
            durationMs = endMs - s.startMs,
            value = s.calories,
            value2 = s.distanceMeters
        )
        heartRateSamples.clear()

        scope.launch {
            try {
                HealthServices.getClient(ctx).exerciseClient.clearUpdateCallbackAsync(callback).await()
            } catch (_: Exception) { }
            launch(Dispatchers.IO) {
                HealthDatabase.get(ctx).recordDao().insertAll(records)
                HealthSyncManager.requestSync()
            }
            ctx.stopService(Intent(ctx, WorkoutService::class.java))
            Log.i(TAG, "Workout saved: ${(endMs - s.startMs) / 1000}s, ${s.calories} kcal, ${s.distanceMeters} m")
        }
    }

    private fun exerciseTypeFor(activity: Int): ExerciseType = when (activity) {
        HealthProtocol.ACTIVITY_WALKING -> ExerciseType.WALKING
        HealthProtocol.ACTIVITY_RUNNING -> ExerciseType.RUNNING
        HealthProtocol.ACTIVITY_CYCLING -> ExerciseType.BIKING
        HealthProtocol.ACTIVITY_STRENGTH -> ExerciseType.STRENGTH_TRAINING
        else -> ExerciseType.WORKOUT
    }
}
