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
import java.time.OffsetDateTime
import java.util.UUID

/** Stores only an opaque event ID and trigger time; no title, description, location, or token. */
data class VeerCalendarReminder(val id: String, val eventId: String, val triggerAtMillis: Long)

object VeerCalendarReminderPolicy {
    const val MAX_ACTIVE_REMINDERS = 50
    const val LEAD_TIME_MILLIS = 10 * 60 * 1000L
    const val MIN_FUTURE_MILLIS = 60 * 1000L
    const val MAX_FUTURE_MILLIS = 366L * 24 * 60 * 60 * 1000

    fun create(eventId: String, startDateTime: String, nowMillis: Long = System.currentTimeMillis()): VeerCalendarReminder {
        require(eventId.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid Calendar event ID" }
        val startMillis = try { OffsetDateTime.parse(startDateTime).toInstant().toEpochMilli() }
            catch (_: Exception) { throw IllegalArgumentException("Timed event start must include a timezone offset") }
        val trigger = startMillis - LEAD_TIME_MILLIS
        require(trigger - nowMillis in MIN_FUTURE_MILLIS..MAX_FUTURE_MILLIS) {
            "Reminder must be at least 1 minute and no more than 366 days in the future"
        }
        return VeerCalendarReminder(UUID.randomUUID().toString(), eventId, trigger)
    }

    fun encode(reminders: List<VeerCalendarReminder>): String {
        require(reminders.size <= MAX_ACTIVE_REMINDERS) { "Reminder limit reached" }
        val array = JSONArray()
        reminders.forEach {
            require(it.id.matches(Regex("[A-Fa-f0-9-]{36}"))) { "Invalid reminder ID" }
            require(it.eventId.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Invalid event ID" }
            require(it.triggerAtMillis > 0L) { "Invalid reminder time" }
            array.put(JSONObject().put("id", it.id).put("eventId", it.eventId).put("triggerAtMillis", it.triggerAtMillis))
        }
        return array.toString()
    }

    fun decode(raw: String): List<VeerCalendarReminder> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until minOf(array.length(), MAX_ACTIVE_REMINDERS)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val eventId = item.optString("eventId")
                val trigger = item.optLong("triggerAtMillis", -1L)
                if (!id.matches(Regex("[A-Fa-f0-9-]{36}")) ||
                    !eventId.matches(Regex("[A-Za-z0-9_-]{1,256}")) || trigger <= 0L) null
                else VeerCalendarReminder(id, eventId, trigger)
            }
        } catch (_: Exception) { emptyList() }
    }
}

object VeerCalendarReminderStore {
    private const val PREFS = "veer_calendar_local_reminders"
    private const val KEY = "scheduled"
    fun list(context: Context): List<VeerCalendarReminder> =
        VeerCalendarReminderPolicy.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
    fun save(context: Context, reminders: List<VeerCalendarReminder>) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, VeerCalendarReminderPolicy.encode(reminders)).commit()) { "Could not save Calendar reminder" }
    }
    fun remove(context: Context, id: String) { save(context, list(context).filterNot { it.id == id }) }
}

object VeerCalendarReminderScheduler {
    const val CHANNEL_ID = "calendar_event_reminders"

    fun schedule(context: Context, eventId: String, startDateTime: String): VeerCalendarReminder {
        val existing = VeerCalendarReminderStore.list(context)
        require(existing.none { it.eventId == eventId }) { "A reminder already exists for this event" }
        require(existing.size < VeerCalendarReminderPolicy.MAX_ACTIVE_REMINDERS) { "Reminder limit reached" }
        val reminder = VeerCalendarReminderPolicy.create(eventId, startDateTime)
        VeerCalendarReminderStore.save(context, existing + reminder)
        try { scheduleExisting(context, reminder) }
        catch (error: Exception) {
            VeerCalendarReminderStore.remove(context, reminder.id)
            throw error
        }
        return reminder
    }

    fun cancel(context: Context, id: String) {
        val reminder = VeerCalendarReminderStore.list(context).firstOrNull { it.id == id } ?: return
        val pending = alarmIntent(context, reminder)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
        VeerCalendarReminderStore.remove(context, id)
    }

    fun cancelForEvent(context: Context, eventId: String) {
        VeerCalendarReminderStore.list(context).filter { it.eventId == eventId }.forEach { cancel(context, it.id) }
    }

    fun cancelAll(context: Context) {
        VeerCalendarReminderStore.list(context).forEach {
            val pending = alarmIntent(context, it)
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
        VeerCalendarReminderStore.save(context, emptyList())
    }

    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val active = VeerCalendarReminderStore.list(context).filter { it.triggerAtMillis > now }
        VeerCalendarReminderStore.save(context, active)
        active.forEach { scheduleExisting(context, it) }
    }

    private fun scheduleExisting(context: Context, reminder: VeerCalendarReminder) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact alarm avoids the exact-alarm special permission and is battery friendly.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, alarmIntent(context, reminder))
    }

    private fun alarmIntent(context: Context, reminder: VeerCalendarReminder): PendingIntent {
        val intent = Intent(context, VeerCalendarReminderReceiver::class.java)
            .setData(Uri.parse("veer://calendar-reminder/" + reminder.id))
            .putExtra("reminder_id", reminder.id)
        return PendingIntent.getBroadcast(context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class VeerCalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("reminder_id") ?: return
        val reminder = VeerCalendarReminderStore.list(context).firstOrNull { it.id == id } ?: return
        if (reminder.triggerAtMillis > System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            VeerCalendarReminderStore.remove(context, id)
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            VeerCalendarReminderScheduler.CHANNEL_ID, "Calendar reminders", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Local reminders for events you selected in VEER" })
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, VeerCalendarReminderScheduler.CHANNEL_ID)
            else Notification.Builder(context)
        manager.notify(id.hashCode(), builder.setSmallIcon(com.veer.assistant.R.mipmap.ic_launcher)
            .setContentTitle("Calendar event coming up")
            .setContentText("Open VEER or Google Calendar to review your event.")
            .setContentIntent(openApp).setAutoCancel(true).build())
        VeerCalendarReminderStore.remove(context, id)
    }
}

class VeerCalendarReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) VeerCalendarReminderScheduler.restore(context)
    }
}
