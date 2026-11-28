package com.veer.assistant

import java.net.URI

/** Advisory, on-device phishing indicators. This is not a definitive security verdict. */
object VeerGmailSecurity {
    enum class Risk { REVIEW, CAUTION, LOW }

    data class Finding(
        val messageId: String,
        val subject: String,
        val sender: String,
        val risk: Risk,
        val indicators: List<String>
    )

    private val credentialPhrases = listOf(
        "confirm your password", "verify your password", "enter your password",
        "share your otp", "send us the otp", "one time password", "login to restore",
        "verify your account", "account will be suspended", "account has been suspended"
    )
    private val pressurePhrases = listOf(
        "act immediately", "within 24 hours", "urgent action", "final warning",
        "immediate action required", "your account will be closed"
    )
    private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "cutt.ly")

    fun analyze(messages: List<VeerGmailClient.Message>, limit: Int = 10): List<Finding> {
        require(limit in 1..20) { "Finding limit must be between 1 and 20" }
        return messages.take(50).mapNotNull { message ->
            val content = (message.subject + " " + message.snippet + " " + message.bodyText.take(6000)).lowercase()
            val indicators = linkedSetOf<String>()
            if (credentialPhrases.any { it in content }) indicators += "Message asks for password, OTP, or account verification"
            if (pressurePhrases.any { it in content }) indicators += "Uses urgent or threatening language"
            val urls = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).findAll(content)
                .mapNotNull { match -> match.value.trimEnd('.', ',', ')', ']', ';').take(2048).let { runCatching { URI(it) }.getOrNull() } }
                .take(20).toList()
            urls.forEach { uri ->
                val host = uri.host?.lowercase().orEmpty()
                if (host.isBlank()) indicators += "Contains a malformed web link"
                else {
                    if (host.startsWith("xn--") || host.split('.').any { it.startsWith("xn--") }) indicators += "Link uses an internationalized/punycode domain"
                    if (host.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) indicators += "Link points to a raw IP address"
                    if (host in shorteners) indicators += "Link uses a URL-shortening service"
                    if (uri.userInfo != null) indicators += "Link contains misleading embedded login information"
                }
            }
            val senderAddress = Regex("<([^<>\\s]+@[^<>\\s]+)>").find(message.from)?.groupValues?.get(1)
                ?: Regex("([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})", RegexOption.IGNORE_CASE).find(message.from)?.groupValues?.get(1)
            if (senderAddress != null && message.from.substringBefore('<').trim().let { display ->
                    display.isNotBlank() && display.contains("@").not() && display.length > 2 &&
                        display.lowercase() in setOf("google", "microsoft", "apple", "paypal", "amazon", "bank")
                }) {
                val senderDomain = senderAddress.substringAfter('@').lowercase()
                val trustedBrandDomains = mapOf("google" to "google.com", "microsoft" to "microsoft.com", "apple" to "apple.com", "paypal" to "paypal.com", "amazon" to "amazon.com", "bank" to "")
                val brand = message.from.substringBefore('<').trim().lowercase()
                val expected = trustedBrandDomains[brand]
                if (expected != null && expected.isNotBlank() && senderDomain != expected && !senderDomain.endsWith(".$expected")) {
                    indicators += "Sender display name does not match the expected brand domain"
                }
            }
            if (indicators.isEmpty()) null else Finding(
                messageId = message.id,
                subject = message.subject.ifBlank { "(No subject)" }.take(180),
                sender = message.from.ifBlank { "Unknown sender" }.take(180),
                risk = if (indicators.size >= 2 || indicators.any { it.contains("password, OTP") || it.contains("display name") }) Risk.REVIEW else Risk.CAUTION,
                indicators = indicators.toList()
            )
        }.sortedWith(compareByDescending<Finding> { it.risk == Risk.REVIEW }.thenBy { it.subject.lowercase() }).take(limit)
    }
}
