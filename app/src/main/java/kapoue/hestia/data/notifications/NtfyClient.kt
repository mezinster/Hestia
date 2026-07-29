package kapoue.hestia.data.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envoi direct vers ntfy **depuis le téléphone** — réservé au bouton « Tester » et au test groupé
 * des textes de notif. En usage réel, c'est **l'appareil Shelly lui-même** qui appelle ntfy (via
 * `HTTP.POST` dans son planning/script), pas Hestia : ce client ne sert qu'à la validation.
 */
@Singleton
class NtfyClient @Inject constructor(private val httpClient: OkHttpClient) {

    suspend fun send(topic: String, title: String, message: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://ntfy.sh/$topic")
                .post(message.toRequestBody(TEXT_PLAIN))
                .header("Title", title)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private companion object {
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
