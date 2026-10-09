package com.omymaxz.download

import org.junit.Test
import org.junit.Assert.*

class DoubaoBlobTest {
    @Test
    fun testBlobChunkingLogicSimulated() {
        // We simulate the concatenation of chunks
        val chunks = listOf(
            "U29tZSBmYWtlIGJhc2U2NCBkYXRh", // "Some fake base64 data"
            "bW9yZSBkYXRh", // "more data"
            "YW5kIG1vcmU=" // "and more"
        )

        val stringBuilder = StringBuilder()
        for (chunk in chunks) {
            stringBuilder.append(chunk)
        }

        assertEquals("U29tZSBmYWtlIGJhc2U2NCBkYXRhbW9yZSBkYXRhYW5kIG1vcmU=", stringBuilder.toString())
    }
}
