package com.chill.familyvlog.ai

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SourceBytesReaderTest {
    @Test
    fun `unknown length reads no more than max plus one byte`() = runTest {
        val input = CountingInputStream(size = 100)

        val result = readBoundedSource(input, maxBytes = 10)

        assertEquals(SourceBytesReadResult.TooLarge, result)
        assertEquals(11, input.bytesRead)
    }

    @Test
    fun `input at the byte limit remains unchanged`() = runTest {
        val expected = byteArrayOf(0, 1, 2, 0x7f)

        val result = readBoundedSource(ByteArrayInputStream(expected), maxBytes = expected.size)

        assertArrayEquals(expected, (result as SourceBytesReadResult.Fits).bytes)
    }

    @Test
    fun `input cancellation is propagated unchanged`() = runTest {
        val cancellation = CancellationException("read cancelled")
        val input = object : InputStream() {
            override fun read(): Int = throw cancellation

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw cancellation
        }

        val failure = runCatching { readBoundedSource(input, maxBytes = 10) }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    private class CountingInputStream(private val size: Int) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int {
            if (bytesRead == size) return -1
            bytesRead += 1
            return bytesRead and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead == size) return -1
            val count = minOf(length, size - bytesRead)
            repeat(count) { index -> buffer[offset + index] = ((bytesRead + index + 1) and 0xff).toByte() }
            bytesRead += count
            return count
        }
    }
}
