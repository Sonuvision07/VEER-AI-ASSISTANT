package com.veer.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Opt-in accessibility bridge foundation.
 * This initial stage observes only window-state transitions.
 * It does not read screen text, capture content, click, type, or perform gestures.
 */
class VeerAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately no screen-content inspection or automatic actions in this stage.
    }

    override fun onInterrupt() {
        // No ongoing action to interrupt in the observer-only foundation.
    }
}
