package com.example.fivethreeonelifter

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class WorkoutReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val day = intent.getIntExtra(NotificationScheduler.EXTRA_DAY, -1)
        val slot = intent.getIntExtra(NotificationScheduler.EXTRA_SLOT, -1)
        if (day !in 1..7 || slot !in 0..3) return

        // Schedule next week's occurrence first so one skipped notification never breaks the chain.
        NotificationScheduler.scheduleOne(context, day, slot)

        val today = LocalDate.now()
        if (today.dayOfWeek.value != day) return

        val db = WorkoutDb(context.applicationContext)
        try {
            val scheduled = db.getScheduledDay(day) ?: return
            val templateId = scheduled.templateId ?: return
            val templateName = scheduled.templateName ?: "Workout"

            val zone = ZoneId.systemDefault()
            val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (db.hasCompletedTemplateBetween(templateId, start, end)) {
                NotificationScheduler.dismissDay(context, day)
                return
            }

            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return

            NotificationScheduler.createChannel(context)
            val isActive = db.hasActiveWorkoutForTemplate(templateId)
            val dayName = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
            val title = if (isActive) "Resume $templateName" else "$dayName · $templateName"
            val body = if (isActive) {
                "You already started today's workout. Jump back in and finish your remaining sets."
            } else {
                when (slot) {
                    0 -> "Today's training is $templateName. Open LiftLog when you're ready to start."
                    1 -> "$templateName is still on today's plan. A quick session now keeps the streak moving."
                    2 -> "Training reminder: $templateName is scheduled for today."
                    else -> "Last LiftLog reminder for today: $templateName is still waiting."
                }
            }

            val openApp = PendingIntent.getActivity(
                context,
                9000 + day,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(context, NotificationScheduler.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()

            context.getSystemService(NotificationManager::class.java)
                .notify(NotificationScheduler.notificationId(day), notification)
        } finally {
            db.close()
        }
    }
}
