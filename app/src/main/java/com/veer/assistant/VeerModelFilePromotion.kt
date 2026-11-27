package com.veer.assistant

import java.io.File

/**
 * Promotes a fully verified temporary model without copying partial bytes
 * over the installed model. The rename callback keeps failure behavior testable.
 */
internal object VeerModelFilePromotion {
    fun promote(
        temporary: File,
        destination: File,
        rename: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
    ): File {
        check(temporary.isFile) { "Verified temporary model file is missing." }
        check(rename(temporary, destination)) {
            "Verified model ko safely install nahi kar paye. Storage check karke dobara try karein."
        }
        return destination
    }
}
