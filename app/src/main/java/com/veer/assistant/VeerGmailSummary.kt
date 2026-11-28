package com.veer.assistant

/** Local, deterministic inbox triage. Email text is treated only as data, never as instructions. */
object VeerGmailSummary {
    enum class Priority { HIGH, NORMAL }

    data class Item(
        val messageId: String,
        val subject: String,
        val sender: String,
        val priority: Priority,
        val reason: String,
        val preview: String
    )

    fun summarize(messages: List<VeerGmailClient.Message>, limit: Int = 10): List<Item> {
        require(limit in 1..20) { "Summary limit must be between 1 and 20" }
        return messages.take(50).map { message ->
            val labels = message.labels.map { it.uppercase() }.toSet()
            val text = (message.subject + " " + message.snippet + " " + message.bodyText.take(3000)).lowercase()
            val reasons = mutableListOf<String>()
            if ("IMPORTANT" in labels) reasons += "Gmail marked important"
            if ("STARRED" in labels) reasons += "Starred message"
            val urgentTerms = listOf("urgent", "action required", "payment due", "due today", "deadline", "account suspended", "security alert", "verify your account")
            val matched = urgentTerms.firstOrNull { it in text }
            if (matched != null) reasons += "Contains '$matched'"
            val high = reasons.isNotEmpty()
            Item(
                messageId = message.id,
                subject = message.subject.ifBlank { "(No subject)" }.take(180),
                sender = message.from.ifBlank { "Unknown sender" }.take(180),
                priority = if (high) Priority.HIGH else Priority.NORMAL,
                reason = reasons.joinToString("; ").ifBlank { if ("UNREAD" in labels) "Unread — review when convenient" else "No obvious urgency signal" },
                preview = message.snippet.ifBlank { message.bodyText }.replace(Regex("\\s+"), " ").take(240)
            )
        }.sortedWith(compareByDescending<Item> { it.priority == Priority.HIGH }.thenBy { it.subject.lowercase() }).take(limit)
    }
}
