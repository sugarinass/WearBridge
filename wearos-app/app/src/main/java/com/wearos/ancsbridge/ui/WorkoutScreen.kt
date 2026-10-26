package com.wearos.ancsbridge.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.viewmodel.MainViewModel
import kotlinx.coroutines.delay

private val workoutActivities = listOf(
    HealthProtocol.ACTIVITY_WALKING to "Walk",
    HealthProtocol.ACTIVITY_RUNNING to "Run",
    HealthProtocol.ACTIVITY_CYCLING to "Cycling",
    HealthProtocol.ACTIVITY_STRENGTH to "Strength",
    HealthProtocol.ACTIVITY_OTHER to "Other",
)

/** Home screen entry: pending health records + Workout button. */
@Composable
fun HealthCard(viewModel: MainViewModel, onOpenWorkout: () -> Unit) {
    val pending by viewModel.pendingHealthRecords.collectAsState()
    val lastSync by viewModel.lastHealthSyncMs.collectAsState()
    val workout by viewModel.workoutState.collectAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Button(
            onClick = onOpenWorkout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (workout.active) Color(0xFF16A34A) else Color(0xFF374151)
            ),
            icon = {
                Icon(Icons.Rounded.FitnessCenter, contentDescription = null)
            }
        ) {
            Text(if (workout.active) "Workout active" else "Workout", fontSize = 12.sp)
        }
        Text(
            when {
                pending > 0 -> "Apple Health: $pending pending"
                lastSync > 0 -> "Apple Health: synced " +
                    DateUtils.getRelativeTimeSpanString(lastSync)
                else -> "Apple Health: up to date"
            },
            fontSize = 10.sp,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun WorkoutScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val workout by viewModel.workoutState.collectAsState()
    val error by viewModel.workoutError.collectAsState()

    // Tick once a second for the elapsed timer
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout.active) {
        while (workout.active) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text(if (workout.active) "Workout" else "Start Workout") } }

        if (workout.active) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DateUtils.formatElapsedTime((now.longValue - workout.startMs) / 1000),
                        fontSize = 26.sp
                    )
                    Text(
                        "${workout.heartRate?.toInt() ?: "--"} bpm",
                        fontSize = 14.sp,
                        color = Color(0xFFF87171)
                    )
                    Text(
                        "%.0f kcal · %.2f km".format(workout.calories, workout.distanceMeters / 1000),
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
            }
            item {
                Button(
                    onClick = { viewModel.stopWorkout() },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Finish & Save", fontSize = 12.sp, color = Color.White)
                }
            }
        } else {
            error?.let { message ->
                item {
                    Text(
                        message,
                        fontSize = 11.sp,
                        color = Color(0xFFF87171),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            workoutActivities.forEach { (activity, label) ->
                item {
                    Button(
                        onClick = { viewModel.startWorkout(activity) },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))
            ) {
                Text("Back", fontSize = 12.sp)
            }
        }
    }
}
