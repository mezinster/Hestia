package kapoue.hestia.data.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Programme (ou annule) les passages du [NotificationWorker].
 *
 * WorkManager impose un intervalle plancher de 15 min : c'est la résolution des notifications de
 * bornes. Le worker ne fait que **lire** l'état des appareils et notifier — il ne pilote jamais
 * un appareil (interdit par principe : la programmation vit dans le firmware Shelly).
 *
 * À l'activation, on lance **deux** demandes :
 * - un travail **périodique** (~15 min) = le régime normal ;
 * - un travail **unique immédiat** = une première vérification tout de suite (s'exécute en quelques
 *   secondes). C'est ce qui rend « désactiver puis réactiver » réellement utile pour re-tester
 *   sur-le-champ, sans attendre le prochain passage périodique.
 */
object NotificationScheduler {

    private const val WORK_PERIODIC = "hestia_notifications"
    private const val WORK_IMMEDIATE = "hestia_notifications_now"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)

        val immediate = OneTimeWorkRequestBuilder<NotificationWorker>().build()
        wm.enqueueUniqueWork(WORK_IMMEDIATE, ExistingWorkPolicy.REPLACE, immediate)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_PERIODIC)
        wm.cancelUniqueWork(WORK_IMMEDIATE)
    }
}
