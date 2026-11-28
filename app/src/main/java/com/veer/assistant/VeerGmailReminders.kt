package com.veer.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only opaque Gmail message IDs and trigger timestamps are stored; no email body or subject. */
data class VeerGmailReminder(val id: String, val messageId: String, val triggerAtMillis: Long)

object VeerGmailReminderPolicy {
    const val MAX_ACTIVE_REMINDERS = 50
    const val MIN_DELAY_MILLIS = 60_000L
    const val MAX_DELAY_MILLIS = 365L * 24 * 60 * 60 * 1000

    fun create(messageId: String, delayMillis: Long, nowMillis: Long = System.currentTimeMillis()): VeerGmailReminder {
        require(messageId.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid Gmail message ID" }
        require(delayMillis in MIN_DELAY_MILLIS..MAX_DELAY_MILLIS) { "Reminder must be between 1 minute and 365 days" }
        return VeerGmailReminder(UUID.randomUUID().toString(), messageId, nowMillis + delayMillis)
    }

    fun encode(reminders: List<VeerGmailReminder>): String {
        require(reminders.size <= MAX_ACTIVE_REMINDERS) { "Too many active reminders" }
        val json = JSONArray()
        reminders.forEach { reminder ->
            require(reminder.id.matches(Regex("[A-Fa-f0-9-]{36}"))) { "Invalid reminder ID" }
            require(reminder.messageId.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid Gmail message ID" }
            require(reminder.triggerAtMillis > 0L) { "Invalid reminder time" }
            json.put(JSONObject().put("id", reminder.id).put("messageId", reminder.messageId).put("triggerAtMillis", reminder.triggerAtMillis))
        }
        return json.toString()
    }

    fun decode(raw: String): List<VeerGmailReminder> {
        if (raw.isBlank()) return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until minOf(json.length(), MAX_ACTIVE_REMINDERS)).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val messageId = item.optString("messageId")
                val trigger = item.optLong("triggerAtMillis", -1L)
                if (!id.matches(Regex("[A-Fa-f0-9-]{36}")) ||
                    !messageId.matches(Regex("[A-Za-z0-9_-]{1,256}")) || trigger <= 0L) null
                else VeerGmailReminder(id, messageId, trigger)
            }
        } catch (_: Exception) { emptyList() }
    }
}

object VeerGmailReminderStore {
    private const val PREFS = "veer_gmail_reminders"
    private const val KEY = "scheduled"
    fun list(context: Context): List<VeerGmailReminder> =
        VeerGmailReminderPolicy.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
    fun save(context: Context, reminders: List<VeerGmailReminder>) {
        val encoded = VeerGmailReminderPolicy.encode(reminders)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encoded).commit()) { "Could not save reminder" }
    }
    fun remove(context: Context, id: String) { save(context, list(context).filterNot { it.id == id }) }
}

object VeerGmailReminderScheduler {
    const val CHANNEL_ID = "gmail_follow_up_reminders"
    fun schedule(context: Context, messageId: String, delayMillis: Long): VeerGmailReminder {
        val existing = VeerGmailReminderStore.list(context)
        require(existing.size < VeerGmailReminderPolicy.MAX_ACTIVE_REMINDERS) { "Reminder limit reached" }
        val reminder = VeerGmailReminderPolicy.create(messageId, delayMillis)
        VeerGmailReminderStore.save(context, existing + reminder)
        scheduleExisting(context, reminder)
        return reminder
    }
    fun cancel(context: Context, id: String) {
        val reminder = VeerGmailReminderStore.list(context).firstOrNull { it.id == id } ?: return
        val pending = alarmIntent(context, reminder)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
        VeerGmailReminderStore.remove(context, id)
    }
    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val active = VeerGmailReminderStore.list(context).filter { it.triggerAtMillis > now }
        VeerGmailReminderStore.save(context, active)
        active.forEach { scheduleExisting(context, it) }
    }
    private fun scheduleExisting(context: Context, reminder: VeerGmailReminder) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact, idle-aware alarms avoid exact-alarm permission and are battery-friendly.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, alarmIntent(context, reminder))
    }
    private fun alarmIntent(context: Context, reminder: VeerGmailReminder): PendingIntent {
        val intent = Intent(context, VeerGmailReminderReceiver::class.java)
            .setData(Uri.parse("veer://gmail-reminder/\${reminder.id}"))
            .putExtra("reminder_id", reminder.id)
        return PendingIntent.getBroadcast(context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class VeerGmailReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("reminder_id") ?: return
        val reminder = VeerGmailReminderStore.list(context).firstOrNull { it.id == id } ?: return
        if (reminder.triggerAtMillis > System.currentTimeMillis()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            VeerGmailReminderStore.remove(context, id)
            return
        }
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            VeerGmailReminderScheduler.CHANNEL_ID, "Gmail follow-up reminders", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Private reminders to follow up on an email" })
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, VeerGmailReminderScheduler.CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder.setSmallIcon(com.veer.assistant.R.mipmap.ic_launcher)
            .setContentTitle("Email follow-up reminder")
            .setContentText("Open VEER to review your reminder.")
            .setContentIntent(openApp).setAutoCancel(true).build()
        manager.notify(id.hashCode(), notification)
        VeerGmailReminderStore.remove(context, id)
    }
}

class VeerGmailReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) VeerGmailReminderScheduler.restore(context)
    }
}
