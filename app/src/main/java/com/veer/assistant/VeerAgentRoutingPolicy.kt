package com.veer.assistant

/**
 * askGemini() is entered only after provider selection or fallback has chosen
 * Gemini. The currently selected preference may still be AUTO/OpenAI/Claude,
 * so it must not disable the agent brain for a Gemini fallback.
 */
internal fun shouldUseAgentBrainForGeminiRequest(
    imageBase64: String?,
    attachedText: String?
): Boolean = imageBase64 == null && attachedText.isNullOrBlank()
