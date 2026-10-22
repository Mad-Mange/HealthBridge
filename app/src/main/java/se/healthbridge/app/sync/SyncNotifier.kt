package se.healthbridge.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import se.healthbridge.app.MainActivity
import se.healthbridge.app.R

/**
 * Tells the user when the background sync has stopped working (expired Withings login, revoked
 * Health Connect access). Transient network errors are retried silently instead.
 */
object SyncNotifier {
    private const val CHANNEL_ID = "sync_problems"
    private const val NOTIFICATION_ID = 1

    fun onResult(context: Context, result: SyncEngine.Result) {
        when (result.outcome) {
            SyncEngine.Outcome.SUCCESS -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            SyncEngine.Outcome.FAILED -> show(context, result)
            SyncEngine.Outcome.SKIPPED, SyncEngine.Outcome.RETRY -> Unit
        }
    }

    private fun show(context: Context, result: SyncEngine.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // The home screen still shows the problem.
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Synkproblem", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "När synken från Withings har slutat fungera"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (result.needsLogin) "Logga in på Withings igen" else "Synken från Withings misslyckades"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(result.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.summary))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Hourly retries update the notification without buzzing again.
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
