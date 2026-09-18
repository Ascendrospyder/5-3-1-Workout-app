package com.example.fivethreeonelifter

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object NotificationScheduler {
    const val CHANNEL_ID = "workout_reminders"
    const val EXTRA_DAY = "day_of_week"
    const val EXTRA_SLOT = "reminder_slot"
    private const val ACTION_PREFIX = "com.example.fivethreeonelifter.WORKOUT_REMINDER."
    private val defaultTimes = listOf("08:30", "13:00", "17:30", "20:30")
    private val formatter = DateTimeFormatter.ofPattern("HH:mm")

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Workout reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders for scheduled LiftLog training days"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun reminderTimes(db: WorkoutDb): List<String> {
        val saved = db.getSettingString("reminder_times", defaultTimes.joinToString(","))
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (saved.size == 4 && saved.all { isValidTime(it) }) saved else defaultTimes
    }

    fun saveReminderTimes(db: WorkoutDb, values: List<String>) {
        db.setSettingString("reminder_times", values.joinToString(","))
    }

    fun isValidTime(value: String): Boolean = try {
        LocalTime.parse(value, formatter)
        true
    } catch (_: Exception) {
        false
    }

    fun scheduleAll(context: Context) {
        createChannel(context)
        val db = WorkoutDb(context.applicationContext)
        try {
            cancelAll(context)
            val times = reminderTimes(db)
            db.getWorkoutSchedule()
                .filter { it.templateId != null }
                .forEach { scheduled ->
                    times.indices.forEach { slot ->
                        scheduleOne(context, scheduled.dayOfWeek, slot, times[slot])
                    }
                }
        } finally {
            db.close()
        }
    }

    fun scheduleOne(context: Context, dayOfWeek: Int, slot: Int, timeText: String? = null) {
        val db = WorkoutDb(context.applicationContext)
        val actualTime = try {
            if (db.getScheduledDay(dayOfWeek)?.templateId == null) return
            timeText ?: reminderTimes(db).getOrNull(slot) ?: return
        } finally {
            db.close()
        }
        if (!isValidTime(actualTime)) return

        val now = ZonedDateTime.now()
        val targetDay = DayOfWeek.of(dayOfWeek)
        val targetTime = LocalTime.parse(actualTime, formatter)
        var daysAhead = (targetDay.value - now.dayOfWeek.value + 7) % 7
        var target = now.toLocalDate().plusDays(daysAhead.toLong()).atTime(targetTime).atZone(now.zone)
        if (!target.isAfter(now)) {
            daysAhead += 7
            target = now.toLocalDate().plusDays(daysAhead.toLong()).atTime(targetTime).atZone(now.zone)
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            target.toInstant().toEpochMilli(),
            reminderPendingIntent(context, dayOfWeek, slot)
        )
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        listOf(3, 5, 6, 7).forEach { day ->
            repeat(4) { slot ->
                alarmManager.cancel(reminderPendingIntent(context, day, slot))
            }
        }
    }

    fun dismissDay(context: Context, dayOfWeek: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(dayOfWeek))
    }

    fun notificationId(dayOfWeek: Int): Int = 5000 + dayOfWeek

    private fun reminderPendingIntent(context: Context, dayOfWeek: Int, slot: Int): PendingIntent {
        val intent = Intent(context, WorkoutReminderReceiver::class.java).apply {
            action = "$ACTION_PREFIX$dayOfWeek.$slot"
            putExtra(EXTRA_DAY, dayOfWeek)
            putExtra(EXTRA_SLOT, slot)
        }
        return PendingIntent.getBroadcast(
            context,
            dayOfWeek * 10 + slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
