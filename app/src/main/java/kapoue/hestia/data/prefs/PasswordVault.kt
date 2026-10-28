package kapoue.hestia.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.data.rpc.PasswordState
import kapoue.hestia.data.rpc.StoredPassword
import javax.inject.Inject
import javax.inject.Singleton

/** Coffre des mots de passe d'appareils, par identifiant de canal. */
interface PasswordVault {
    fun read(deviceId: Long): StoredPassword?
    /** `null` efface l'entrée. */
    fun write(deviceId: Long, value: StoredPassword?)
    fun knownIds(): Set<Long>
}

/**
 * Coffre chiffré (Android Keystore, AES-256-GCM) dans un fichier dédié, exclu de toute
 * sauvegarde (voir backup_rules.xml et data_extraction_rules.xml). Volontairement sans logique.
 * Écritures en `commit()` : l'état « refusé » doit survivre à un arrêt brutal.
 */
@Singleton
class EncryptedPasswordVault @Inject constructor(@ApplicationContext context: Context) : PasswordVault {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "hestia_device_passwords",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun read(deviceId: Long): StoredPassword? {
        val password = prefs.getString("device_password_$deviceId", null) ?: return null
        val state = runCatching {
            PasswordState.valueOf(prefs.getString("device_password_state_$deviceId", null) ?: "")
        }.getOrDefault(PasswordState.UNVERIFIED)
        return StoredPassword(password, state)
    }

    override fun write(deviceId: Long, value: StoredPassword?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove("device_password_$deviceId").remove("device_password_state_$deviceId")
        } else {
            editor.putString("device_password_$deviceId", value.password)
                .putString("device_password_state_$deviceId", value.state.name)
        }
        editor.commit()
    }

    override fun knownIds(): Set<Long> =
        prefs.all.keys.mapNotNull { it.removePrefix("device_password_").takeIf { r -> r != it }?.toLongOrNull() }.toSet()
}
