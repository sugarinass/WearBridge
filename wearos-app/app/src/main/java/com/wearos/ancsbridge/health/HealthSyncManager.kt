package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pushes buffered [HealthRecord]s to the iPhone companion app over BLE.
 *
 * A batch is only marked synced once the iPhone notifies an ack saying the
 * records were saved to HealthKit (HealthKit is unavailable while the iPhone
 * is locked, so the iPhone may ask us to retry later).
 */
class HealthSyncManager(
    context: Context,
    private val connectionManager: BleConnectionManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "HealthSyncManager"
        private const val SYNC_INTERVAL_MS = 15 * 60_000L
        private const val ACK_TIMEOUT_MS = 20_000L
        private const val POST_CONNECT_DELAY_MS = 70_000L // after the ANCS grace period
        private const val KEEP_SYNCED_MS = 7L * 24 * 60 * 60_000L

        private val syncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        private val _lastSyncMs = MutableStateFlow(0L)
        val lastSyncMs: StateFlow<Long> = _lastSyncMs.asStateFlow()

        fun requestSync() {
            syncRequests.tryEmit(Unit)
        }
    }

    private val dao = HealthDatabase.get(context).recordDao()
    private val pendingAcks = mutableMapOf<Int, CompletableDeferred<Boolean>>()
    private var nextBatchId = 1

    fun start() {
        scope.launch {
            connectionManager.healthAckEvents.collect { data ->
                val (batchId, status) = HealthProtocol.decodeAck(data) ?: return@collect
                pendingAcks.remove(batchId)?.complete(status == HealthProtocol.ACK_SAVED)
            }
        }

        scope.launch {
            connectionManager.connectionState.collectLatest { state ->
                if (state is ConnectionState.Connected) {
                    delay(POST_CONNECT_DELAY_MS)
                    requestSync()
                }
            }
        }

        scope.launch {
            while (isActive) {
                withTimeoutOrNull(SYNC_INTERVAL_MS) { syncRequests.first() }
                try {
                    syncOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed", e)
                }
            }
        }
    }

    private suspend fun syncOnce() {
        if (connectionManager.connectionState.value !is ConnectionState.Connected) return

        if (!connectionManager.isHealthBridgeAvailable()) {
            // The companion app may have started after we connected
            Log.d(TAG, "Health bridge not available — refreshing services")
            connectionManager.refreshServices()
            return
        }

        val perWrite = HealthProtocol.recordsPerWrite(connectionManager.mtu)
        var sent = 0
        while (true) {
            val batch = dao.unsynced(perWrite)
            if (batch.isEmpty()) break

            val batchId = nextBatchId
            nextBatchId = nextBatchId % 0xFFFF + 1
            val ack = CompletableDeferred<Boolean>()
            pendingAcks[batchId] = ack

            if (!connectionManager.writeHealthBatch(HealthProtocol.encodeBatch(batchId, batch))) {
                pendingAcks.remove(batchId)
                Log.w(TAG, "Batch $batchId write failed, will retry")
                break
            }
            val saved = withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } ?: false
            pendingAcks.remove(batchId)
            if (!saved) {
                Log.i(TAG, "Batch $batchId not saved by iPhone (locked or sync off), will retry")
                break
            }
            dao.markSynced(batch.map { it.id })
            sent += batch.size
        }

        if (sent > 0) {
            Log.i(TAG, "Synced $sent health records to iPhone")
            _lastSyncMs.value = System.currentTimeMillis()
        }
        dao.deleteSyncedBefore(System.currentTimeMillis() - KEEP_SYNCED_MS)
    }
}
