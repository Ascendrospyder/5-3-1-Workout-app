package com.example.fivethreeonelifter

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

object RestTimer {
    private const val CHANNEL = "rest_timer"
    private const val NOTIFICATION_ID = 6000
    private fun preferences(context: Context) = context.getSharedPreferences("rest_timer", Context.MODE_PRIVATE)
    fun endAt(context: Context): Long = preferences(context).getLong("end_at", 0)
    fun workoutId(context: Context): Long = preferences(context).getLong("workout_id", 0)
    fun isComplete(context: Context): Boolean = preferences(context).getBoolean("completed", false)
    fun remainingSeconds(context: Context): Long = ((endAt(context) - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
    fun hasPreciseAlarms(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized fun start(context: Context, workoutId: Long, seconds: Int) {
        require(seconds in 1..3600)
        stop(context)
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        preferences(context).edit().putLong("end_at", end).putLong("workout_id", workoutId).putBoolean("completed", false).commit()
        schedule(context, end)
    }

    @Synchronized fun adjust(context: Context, delta: Int) {
        if (endAt(context) == 0L) return
        val end = (endAt(context) + delta * 1000L).coerceAtLeast(SystemClock.elapsedRealtime() + 1000)
        preferences(context).edit().putLong("end_at", end).commit()
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, 0))
        schedule(context, end)
    }

    fun reschedule(context: Context) {
        val end = endAt(context)
        if (end > SystemClock.elapsedRealtime()) schedule(context, end)
    }

    private fun schedule(context: Context, end: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, end)
        try {
            if (hasPreciseAlarms(context)) manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pending)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pending)
        }
    }

    @Synchronized fun stop(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, 0))
        preferences(context).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun pendingIntent(context: Context, end: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 6000, Intent(context, RestTimerReceiver::class.java).putExtra("end_at", end),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** UI polling and the receiver share a deadline claim, preventing duplicate alerts. */
    @Synchronized fun deliver(context: Context, expectedEnd: Long): Boolean {
        if (expectedEnd == 0L || endAt(context) != expectedEnd || SystemClock.elapsedRealtime() < expectedEnd) return false
        val workoutId = workoutId(context)
        preferences(context).edit().remove("end_at").putBoolean("completed", true).commit()
        val db = WorkoutDb(context)
        val options = try {
            if (db.getWorkout(workoutId)?.status != WorkoutSummary.ACTIVE) return false
            (db.getSettingInt("rest_vibrate", 1) == 1) to (db.getSettingInt("rest_sound", 1) == 1)
        } finally { db.close() }
        val notificationAllowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (notificationAllowed) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Channels have immutable sound/vibration settings; use a separate channel per preference pair.
            val channelId = "$CHANNEL.${options.first}.${options.second}"
            manager.createNotificationChannel(NotificationChannel(channelId, "Rest complete", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(options.first)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
                if (!options.second) setSound(null, null)
            })
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).putExtra("workout_id", workoutId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION_ID, Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Rest complete")
                .setContentText("Ready for your next set").setContentIntent(open).setAutoCancel(true).build())
        } else if (options.first) {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 100, 250), -1))
        }
        return true
    }
}

class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RestTimer.deliver(context, intent.getLongExtra("end_at", 0))
    }
}
