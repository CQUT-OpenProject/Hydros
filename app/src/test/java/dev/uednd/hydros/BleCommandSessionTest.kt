package dev.uednd.hydros

import dev.uednd.hydros.data.ble.BleCommandSession
import dev.uednd.hydros.data.ble.BleFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BleCommandSessionTest {
    private class Wire {
        val chunks = mutableListOf<ByteArray>()
        var disconnects = 0
        var accept: (ByteArray) -> Boolean = { true }
        val commands = BleCommandSession(writeChunk = {
            chunks += it.copyOf()
            accept(it)
        }, disconnect = { disconnects++ })

        fun reply(cmd: Int, payload: ByteArray) = commands.onNotification(BleFrame.cmdToWire(cmd, payload))
        fun acknowledge(ok: Boolean = true) = commands.onWriteCompleted(ok)
    }

    @Test fun splitNotificationsDecodeQueryAfterWriteCallback() = runTest {
        val wire = Wire()
        val response = async { wire.commands.query() }
        runCurrent()
        assertArrayEquals(BleFrame.cmdToWire(0x23), wire.chunks.single())
        val payload = ByteArray(31).apply {
            this[19] = 2; this[24] = 1; this[25] = 2; this[26] = 3; this[27] = 4
            this[28] = 1; this[29] = 5; this[30] = 6
        }
        val frame = BleFrame.cmdToWire(0x23, payload)
        wire.commands.onNotification(frame.copyOfRange(0, 9))
        wire.commands.onNotification(frame.copyOfRange(9, frame.size))
        runCurrent()
        assertFalse(response.isCompleted)
        wire.acknowledge()
        val state = response.await()!!
        assertEquals("02", state.protocolType)
        assertEquals("01020304", state.randomNumber)
        assertEquals(1, state.state)
        assertEquals(5, state.devType)
        assertEquals(6, state.a1)
    }

    @Test fun joinedMalformedAndWrongCommandFramesDoNotHideExpectedReply() = runTest {
        val wire = Wire()
        val response = async { wire.commands.closeValve() }
        runCurrent()
        wire.acknowledge()
        wire.commands.onNotification("broken\n".toByteArray() +
            BleFrame.cmdToWire(0x21, byteArrayOf(0x80.toByte())) +
            BleFrame.cmdToWire(0x22, byteArrayOf(0x80.toByte())))
        assertTrue(response.await())
        assertEquals(0, wire.disconnects)
    }

    @Test fun chunksWaitForCallbacksAndKeepWireOrderWithoutReplay() = runTest {
        val wire = Wire()
        val payload = ByteArray(24) { it.toByte() }
        val response = async { wire.commands.writeRate(payload) }
        runCurrent()
        val expected = BleFrame.cmdToWire(0x21, payload)
        while (!response.isCompleted) {
            assertTrue(wire.chunks.all { it.size <= 20 })
            val count = wire.chunks.size
            advanceTimeBy(100)
            runCurrent()
            assertEquals(count, wire.chunks.size)
            wire.acknowledge()
            runCurrent()
            if (wire.chunks.sumOf { it.size } == expected.size) {
                wire.reply(0x21, byteArrayOf(0x80.toByte()))
                break
            }
            advanceTimeBy(20)
            runCurrent()
        }
        assertTrue(response.await())
        assertArrayEquals(expected, wire.chunks.fold(byteArrayOf()) { bytes, chunk -> bytes + chunk })
    }

    @Test fun rejectedWriteAndFailedCallbackDisconnectWithoutReplaying() = runTest {
        for (reject in listOf(true, false)) {
            val wire = Wire()
            wire.accept = { !reject }
            val response = async { wire.commands.writeBack(ByteArray(24)) }
            runCurrent()
            if (!reject) wire.acknowledge(false)
            assertFalse(response.await())
            assertEquals(1, wire.chunks.size)
            assertEquals(1, wire.disconnects)
        }
    }

    @Test fun disconnectionUnblocksWriteAndReplyWaits() = runTest {
        for (afterWrite in listOf(false, true)) {
            val wire = Wire()
            val response = async { wire.commands.collect() }
            runCurrent()
            if (afterWrite) { wire.acknowledge(); runCurrent() }
            wire.commands.onDisconnected()
            assertNull(response.await())
            assertEquals(0, wire.disconnects)
        }
    }

    @Test fun cancellingWriteOrReplyDisconnectsAndPropagatesCancellation() = runTest {
        for (afterWrite in listOf(false, true)) {
            val wire = Wire()
            val response = launch { wire.commands.collect() }
            runCurrent()
            if (afterWrite) { wire.acknowledge(); runCurrent() }
            response.cancelAndJoin()
            assertTrue(response.isCancelled)
            assertEquals(1, wire.disconnects)
            assertNull(wire.commands.collect())
            assertEquals(1, wire.chunks.size)
        }
    }

    @Test fun writeTimeoutAndReplyTimeoutRetireConnectionAndIgnoreLateFrames() = runTest {
        for (afterWrite in listOf(false, true)) {
            val old = Wire()
            val response = async { old.commands.collect(timeoutMs = 500) }
            runCurrent()
            if (afterWrite) { old.acknowledge(); runCurrent() }
            advanceTimeBy(if (afterWrite) 500 else 3_000)
            runCurrent()
            assertNull(response.await())
            assertEquals(1, old.disconnects)
            old.reply(0x85, byteArrayOf(9))
            old.acknowledge()
            assertNull(old.commands.collect())
            val fresh = Wire()
            val next = async { fresh.commands.collect() }
            runCurrent()
            fresh.acknowledge()
            runCurrent()
            assertFalse(next.isCompleted)
            fresh.reply(0x85, byteArrayOf(7))
            assertArrayEquals(byteArrayOf(7), next.await())
        }
    }

    @Test fun shortQueryAndNegativeOrEmptyAcknowledgmentsAreNotSuccess() = runTest {
        val wire = Wire()
        val query = async { wire.commands.query() }
        runCurrent(); wire.acknowledge(); wire.reply(0x23, ByteArray(30))
        assertNull(query.await())
        val rate = async { wire.commands.writeRate(byteArrayOf()) }
        runCurrent(); wire.acknowledge(); wire.reply(0x21, byteArrayOf(0))
        assertFalse(rate.await())
        val back = async { wire.commands.writeBack(byteArrayOf()) }
        runCurrent(); wire.acknowledge(); wire.reply(0x86, byteArrayOf())
        assertFalse(back.await())
    }

    @Test fun enclosingTimeoutCannotBeSwallowedByCommandDeadline() = runTest {
        val wire = Wire()
        var returnedNormally = false
        val response = async {
            withTimeoutOrNull(100) {
                wire.commands.collect()
                returnedNormally = true
            }
        }
        runCurrent()
        wire.acknowledge()
        advanceTimeBy(100)
        runCurrent()
        assertNull(response.await())
        assertFalse(returnedNormally)
        assertEquals(1, wire.disconnects)
    }

    @Test fun cancellingBetweenChunksNeverSubmitsRemainingBytes() = runTest {
        val wire = Wire()
        val response = launch { wire.commands.writeRate(ByteArray(24)) }
        runCurrent()
        wire.acknowledge()
        runCurrent()
        response.cancelAndJoin()
        advanceTimeBy(20)
        runCurrent()
        assertEquals(1, wire.chunks.size)
        assertEquals(1, wire.disconnects)
    }
}
