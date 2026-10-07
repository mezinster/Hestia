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

/**
 * v6 → v7 : réglage personnalisé du minuteur « Active pour » (durée + seuil de coupure
 * optionnel), enregistrable par l'utilisateur. Colonnes nullables : aucune valeur par défaut
 * nécessaire, absence = aucun réglage enregistré pour cet appareil.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN presetDurationSeconds INTEGER")
        db.execSQL("ALTER TABLE devices ADD COLUMN presetThresholdW INTEGER")
    }
}

/**
 * v7 → v8 : un 2ᵉ réglage personnalisé (« Perso 1 »/« Perso 2 ») et un nom pour chacun. Colonnes
 * nullables : absence = emplacement vide, comme pour le premier réglage en v6→v7.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN presetName TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN preset2DurationSeconds INTEGER")
        db.execSQL("ALTER TABLE devices ADD COLUMN preset2ThresholdW INTEGER")
        db.execSQL("ALTER TABLE devices ADD COLUMN preset2Name TEXT")
    }
}

/**
 * v8 → v9 : deuxième adresse IP nommable par appareil (ex. domicile / vacances), avec bascule
 * automatique. Colonnes nullables : absence de 2ᵉ emplacement = comportement inchangé (une seule
 * adresse, comme avant cette version).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN ip2Address TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN ipName TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN ip2Name TEXT")
        db.execSQL("ALTER TABLE devices ADD COLUMN lastWorkingIpSlot INTEGER")
    }
}

/**
 * v9 → v10 : plannings mis en pause (mémo local le temps de la pause — voir [PausedPlanning]).
 * Nouvelle table, aucune colonne existante touchée.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `paused_plannings` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deviceId` INTEGER NOT NULL, " +
                "`startHour` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, " +
                "`endHour` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, " +
                "`days` TEXT NOT NULL, `date` TEXT, `cutoffThresholdW` INTEGER, " +
                "`pausedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`deviceId`) REFERENCES `devices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_paused_plannings_deviceId` ON `paused_plannings` (`deviceId`)",
        )
    }
}

/**
 * v10 → v11 : réglages Perso sans limite de durée (coupure sur seuil uniquement). Colonnes
 * booléennes non nulles : défaut à 0 (faux) pour les réglages déjà enregistrés, comportement
 * inchangé pour eux.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN presetUnlimited INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE devices ADD COLUMN preset2Unlimited INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v11 → v12 : nom d'appareil stable, distinct du nom (renommable) de chaque canal — voir
 * [kapoue.hestia.data.local.entity.Device.deviceName]. Vide par défaut pour les appareils déjà
 * enregistrés ; rempli au premier lancement suivant par `DeviceRepository.fixLegacyChannelNames`
 * (logique Kotlin, pas faisable proprement en SQL pur ici).
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN deviceName TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v12 → v14 (jamais 13, voir CLAUDE.md) : fusion Planning/Présence (2026-08-18) — un planning en
 * pause peut désormais être une simulation de présence, [PausedPlanning.marginMinutes] non nul
 * dans ce cas. Colonne nullable : absence = planning précis, comportement inchangé pour les
 * plannings déjà en pause.
 */
val MIGRATION_12_14 = object : Migration(12, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE paused_plannings ADD COLUMN marginMinutes INTEGER")
    }
}

/**
 * v14 → v15 : Cloud Shelly, repli à distance, lot 2 (2026-08-20) — cache du MAC de l'appareil
 * physique (« Cloud ID »), lu une fois en local, disponible ensuite même hors réseau pour le
 * repli cloud (lot 3). Colonne nullable, absence = jamais lu, comportement inchangé.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN cloudId TEXT")
    }
}

/**
 * v15 → v16 : suppression de `presence_configs` — table de confort prévue lors du lot 1 pour la
 * présence, jamais réellement branchée après la fusion Planning/Présence du 2026-08-18, qui a
 * fait de la présence une simple variante de [kapoue.hestia.domain.model.Planning] lue en direct
 * depuis le script de l'appareil, comme un planning précis. Plus rien n'écrivait dans cette table
 * depuis (code mort trouvé le 2026-08-24, voir BACKLOG.md) : aucune configuration perdue, il n'y
 * avait plus rien de vivant dedans.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS presence_configs")
    }
}

/**
 * v16 → v17 : vérification périodique du firmware via le Cloud (2026-09-23) — résultat de la
 * dernière vérification automatique (une fois par jour maximum, au lancement, uniquement pour un
 * appareil avec le Cloud Shelly déjà activé, voir CLAUDE.md). Colonne booléenne, absence =
 * jamais vérifié ou à jour, comportement inchangé (pas de bandeau).
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN firmwareUpdateAvailable INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v17 → v18 : variateurs (`light:N`, 2026-10-07, fork) — distingue un canal light d'un relais.
 * Absence = relais, comportement inchangé pour tous les appareils existants.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN isLight INTEGER NOT NULL DEFAULT 0")
    }
}

/** v18 → v19 : volets (lot S1, fork) — distingue un canal cover d'un relais. Absence = relais. */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE devices ADD COLUMN isCover INTEGER NOT NULL DEFAULT 0")
    }
}
