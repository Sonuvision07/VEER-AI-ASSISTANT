package com.veer.assistant

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerModelFilePromotionTest {
    @Test
    fun failedPromotionKeepsPreviouslyInstalledModelUntouched() {
        val directory = Files.createTempDirectory("veer-model-failure").toFile()
        try {
            val temporary = File(directory, "model.download").apply { writeText("new-verified-model") }
            val destination = File(directory, "model.litertlm").apply { writeText("previous-model") }

            try {
                VeerModelFilePromotion.promote(temporary, destination) { _, _ -> false }
                throw AssertionError("Expected promotion to fail")
            } catch (_: IllegalStateException) {
                // Expected: a failed rename must not trigger a copy fallback.
            }

            assertEquals("previous-model", destination.readText())
            assertTrue("Temporary file remains available for caller cleanup", temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulPromotionMovesVerifiedFileIntoPlace() {
        val directory = Files.createTempDirectory("veer-model-success").toFile()
        try {
            val temporary = File(directory, "model.download").apply { writeText("verified-model") }
            val destination = File(directory, "model.litertlm")

            val installed = VeerModelFilePromotion.promote(temporary, destination)

            assertEquals(destination, installed)
            assertEquals("verified-model", destination.readText())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun promotionRejectsMissingTemporaryFile() {
        val directory = Files.createTempDirectory("veer-model-missing").toFile()
        try {
            val missing = File(directory, "missing.download")
            val destination = File(directory, "model.litertlm")

            try {
                VeerModelFilePromotion.promote(missing, destination)
                throw AssertionError("Expected missing file to be rejected")
            } catch (_: IllegalStateException) {
                // Expected.
            }

            assertFalse(destination.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
