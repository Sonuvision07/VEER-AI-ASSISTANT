package com.veer.assistant

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads provider HTTP bodies with a strict byte ceiling.
 * Prevents unexpectedly large responses from exhausting app memory.
 */
internal object VeerBoundedHttpBody {
    const val DEFAULT_MAX_BYTES = 1_048_576

    fun readUtf8(input: InputStream, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - total) {
                throw IllegalStateException("AI provider response exceeded VEER's size limit.")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
