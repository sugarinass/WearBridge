package com.wearos.ancsbridge.health

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for the Health Data Bridge (watch → iPhone companion → HealthKit).
 *
 * Batch (written to the Health Data characteristic, write-with-response):
 *   [version u8][batchId u16 LE][record × N]
 *
 * Record (26 bytes, little-endian):
 *   [type u8][subtype u8][startEpochSec u32][durationSec u32][value f64][value2 f64]
 *
 * Ack (notified on the Health Ack characteristic after HealthKit save):
 *   [batchId u16 LE][status u8]  — 0x00 = saved, 0x01 = retry later
 *
 * Must match ios-app/AncsBridge/Health/HealthRecord.swift.
 */
object HealthProtocol {

    const val VERSION: Byte = 1
    const val HEADER_SIZE = 3
    const val RECORD_SIZE = 26

    // Record types
    const val TYPE_STEPS = 1        // value = count
    const val TYPE_DISTANCE = 2     // value = meters
    const val TYPE_FLOORS = 3       // value = floors
    const val TYPE_HEART_RATE = 4   // value = bpm, duration = 0
    const val TYPE_SLEEP = 5        // interval only
    const val TYPE_WORKOUT = 16     // subtype = activity, value = kcal, value2 = meters

    // Workout activities (subtype for TYPE_WORKOUT)
    const val ACTIVITY_OTHER = 0
    const val ACTIVITY_WALKING = 1
    const val ACTIVITY_RUNNING = 2
    const val ACTIVITY_CYCLING = 3
    const val ACTIVITY_STRENGTH = 4

    const val ACK_SAVED: Int = 0x00
    const val ACK_RETRY: Int = 0x01

    /** How many records fit in one ATT write for the negotiated MTU. */
    fun recordsPerWrite(mtu: Int): Int =
        ((mtu - 3 - HEADER_SIZE) / RECORD_SIZE).coerceIn(1, 255)

    fun encodeBatch(batchId: Int, records: List<HealthRecord>): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + RECORD_SIZE * records.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION)
        buf.putShort(batchId.toShort())
        for (r in records) {
            buf.put(r.type.toByte())
            buf.put(r.subtype.toByte())
            buf.putInt(((r.startMs / 1000) and 0xFFFFFFFFL).toInt())
            buf.putInt(((r.durationMs / 1000) and 0xFFFFFFFFL).toInt())
            buf.putDouble(r.value)
            buf.putDouble(r.value2)
        }
        return buf.array()
    }

    /** Returns (batchId, status) or null if malformed. */
    fun decodeAck(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 3) return null
        val batchId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        return batchId to (data[2].toInt() and 0xFF)
    }
}
