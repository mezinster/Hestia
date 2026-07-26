package kapoue.hestia.data.notifications

import android.Manifest
import android.annotation.SuppressLint
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
import kapoue.hestia.MainActivity
import kapoue.hestia.R

/**
 * Poste les notifications de bornes de programmation (début/fin de planning, de plage de présence).
 * Un seul canal, importance par défaut. Le tap ouvre l'application.
 *
 * Ne pousse rien tant que l'autorisation `POST_NOTIFICATIONS` (Android 13+) n'est pas accordée :
 * elle n'est demandée qu'à l'activation des notifications dans les Réglages.
 */
internal object ProgrammationNotifier {

    private const val CHANNEL_ID = "hestia_programmation"

    /** Vrai si l'application a le droit de poster (toujours vrai avant Android 13). */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Garde-fou explicite via canPost() juste au-dessus.
    fun post(context: Context, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        ensureChannel(context)

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Crée le canal une fois (idempotent). */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_desc) },
        )
    }
}
