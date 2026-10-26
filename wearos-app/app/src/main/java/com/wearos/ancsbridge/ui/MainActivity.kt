package com.wearos.ancsbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import com.wearos.ancsbridge.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startService()
        }
        requestHealthPermissionsIfNeeded()
    }

    // Health permissions are optional — notifications work without them
    private val healthPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) viewModel.startService() // re-registers passive health
        requestBackgroundSensorsIfNeeded()
    }

    // Background heart rate needs a separate "Allow all the time" grant
    private val backgroundSensorsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            AncsBridgeTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            viewModel.startService()
            requestHealthPermissionsIfNeeded()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestHealthPermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS,
        ).filter { !isGranted(it) }

        if (needed.isEmpty()) {
            requestBackgroundSensorsIfNeeded()
        } else {
            healthPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestBackgroundSensorsIfNeeded() {
        if (isGranted(Manifest.permission.BODY_SENSORS) &&
            !isGranted(Manifest.permission.BODY_SENSORS_BACKGROUND)) {
            backgroundSensorsLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
