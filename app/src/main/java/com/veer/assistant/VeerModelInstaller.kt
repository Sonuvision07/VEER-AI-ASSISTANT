package com.veer.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads the public Apache-2.0 Qwen3 0.6B INT4 model to app-private storage.
 * This is a one-time model download, not a hosted inference API.
 */
object VeerModelInstaller {
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    private const val EXPECTED_SHA256 =
        "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"

    /**
     * @param onProgress receives downloaded and expected bytes; called on an IO thread.
     */
    suspend fun install(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val destination = VeerOnDeviceAiEngine.modelFile(context)
        if (destination.isFile && sha256(destination) == EXPECTED_SHA256) {
            return@withContext destination
        }

        val modelDirectory = destination.parentFile ?: error("Model storage directory unavailable.")
        check(modelDirectory.usableSpace >= 500L * 1024L * 1024L) {
            "Offline model download ke liye kam se kam 500 MB free storage chahiye."
        }
        val temporary = File(modelDirectory, destination.name + ".download")
        temporary.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VEER-Android/1.0")
        }

        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Model download server ne HTTP ${connection.responseCode} diya."
            }
            val contentLength = connection.contentLengthLong
            check(contentLength in (300L * 1024L * 1024L)..(400L * 1024L * 1024L)) {
                "Downloaded model ka size expected range mein nahi hai."
            }

            var downloaded = 0L
            var lastProgressReport = 0L
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastProgressReport >= 1024L * 1024L || downloaded == contentLength) {
                            onProgress(downloaded, contentLength)
                            lastProgressReport = downloaded
                        }
                    }
                }
            }

            check(downloaded == contentLength) {
                "Model download adhura hai. Dobara try karein."
            }
            check(sha256(temporary) == EXPECTED_SHA256) {
                "Model file verification fail hui. Corrupt download delete kar diya."
            }
            // Install the verified file with a same-directory rename. Never
            // stream-copy over the destination: a failed copy could leave a
            // partially written model that looks installed to the UI.
            check(temporary.renameTo(destination)) {
                "Verified model ko safely install nahi kar paye. Storage check karke dobara try karein."
            }
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
