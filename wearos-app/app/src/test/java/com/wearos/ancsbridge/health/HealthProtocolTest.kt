package com.wearos.ancsbridge.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HealthProtocolTest {

    @Test
    fun encodeBatch_layout() {
        val record = HealthRecord(
            type = HealthProtocol.TYPE_WORKOUT,
            subtype = HealthProtocol.ACTIVITY_RUNNING,
            startMs = 1_790_000_000_500L,
            durationMs = 1_800_999L,
            value = 312.5,
            value2 = 5_012.0
        )
        val bytes = HealthProtocol.encodeBatch(0xABCD, listOf(record))
        assertEquals(HealthProtocol.HEADER_SIZE + HealthProtocol.RECORD_SIZE, bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(HealthProtocol.VERSION, buf.get())
        assertEquals(0xABCD, buf.short.toInt() and 0xFFFF)
        assertEquals(HealthProtocol.TYPE_WORKOUT, buf.get().toInt())
        assertEquals(HealthProtocol.ACTIVITY_RUNNING, buf.get().toInt())
        assertEquals(1_790_000_000L, buf.int.toLong() and 0xFFFFFFFFL)
        assertEquals(1_800, buf.int)
        assertEquals(312.5, buf.double, 0.0)
        assertEquals(5_012.0, buf.double, 0.0)
    }

    @Test
    fun encodeBatch_startAfter2038_keepsUnsignedBits() {
        val record = HealthRecord(type = 1, startMs = 3_000_000_000_000L, durationMs = 0, value = 1.0)
        val buf = ByteBuffer.wrap(HealthProtocol.encodeBatch(1, listOf(record)))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3_000_000_000L, buf.getInt(5).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun recordsPerWrite_respectsMtu() {
        assertEquals(1, HealthProtocol.recordsPerWrite(23))
        assertEquals(6, HealthProtocol.recordsPerWrite(185))
        assertEquals(19, HealthProtocol.recordsPerWrite(512))
    }

    @Test
    fun decodeAck() {
        assertEquals(0x1234 to HealthProtocol.ACK_RETRY,
            HealthProtocol.decodeAck(byteArrayOf(0x34, 0x12, 0x01)))
        assertNull(HealthProtocol.decodeAck(byteArrayOf(0x01)))
    }
}
