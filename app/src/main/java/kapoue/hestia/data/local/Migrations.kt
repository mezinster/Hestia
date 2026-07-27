package kapoue.hestia.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations Room. Toute évolution de schéma ajoute une migration ici et incrémente la version
 * de [HestiaDatabase] — jamais de migration destructive qui perdrait les appareils de l'utilisateur.
 */

/**
 * v1 → v2 : ajout des capacités détectées sur `Device` (relais, scripts, mesure).
 * Valeurs par défaut sûres pour les appareils déjà enregistrés : ce sont des prises Gen2+
 * (relais + scripts présents) ; la mesure reste informative, on la laisse à 0.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN supportsSwitch INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE devices ADD COLUMN hasScripting INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE devices ADD COLUMN hasPowerMetering INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v2 → v3 : dernier état de sortie observé, persisté pour tracer les extinctions manquées
 * (fin de minuteur hors ligne). Colonne nullable : aucune valeur par défaut nécessaire.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN lastKnownOutput INTEGER")
    }
}

/** v3 → v4 : table du journal de diagnostic (tampon circulaire). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diagnostic_logs` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`level` TEXT NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_diagnostic_logs_timestamp` ON `diagnostic_logs` (`timestamp`)",
        )
    }
}

/**
 * v4 → v5 : origine d'une bascule ON/OFF observée (journal d'activité). Colonne nullable :
 * aucune valeur par défaut nécessaire, les entrées déjà en base restent sans cause connue.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activation_logs ADD COLUMN cause TEXT")
    }
}

/**
 * v5 → v6 : suppression du journal d'activité. Sans tâche de fond permanente, il ratait trop
 * d'événements survenus application fermée pour rester fiable (voir discussion 2026-07-27) —
 * retiré plutôt que maintenu à moitié fonctionnel. Aucune configuration perdue : ce journal
 * n'était que consultatif, les appareils et leurs réglages ne sont pas concernés.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS activation_logs")
    }
}
