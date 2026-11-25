package com.veer.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Explicitly opt-in bridge. The first actionable capability is a single BACK action,
 * invoked only from the app after the user confirms it. No screen text is inspected.
 */
class VeerAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screen-content inspection, logging, or automatic interaction.
    }

    override fun onInterrupt() {
        // No ongoing action to interrupt.
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeService: VeerAccessibilityService? = null

        /** Returns false when the user has not enabled the service in Android Settings. */
        fun performApprovedBack(): Boolean {
            val service = activeService ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
}
