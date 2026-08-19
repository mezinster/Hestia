package kapoue.hestia.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.DiagnosticLogDao
import kapoue.hestia.data.local.dao.PausedPlanningDao
import kapoue.hestia.data.local.dao.PresenceConfigDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.DiagnosticLog
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.local.entity.PresenceConfig

/**
 * Base Room de l'application. Le schéma des 3 entités est figé dès le lot 1 pour éviter
 * les migrations douloureuses ; incrémenter `version` et fournir une Migration à chaque
 * changement de schéma ultérieur.
 */
@Database(
    entities = [Device::class, PresenceConfig::class, DiagnosticLog::class, PausedPlanning::class],
    version = 14, // jamais 13, voir CLAUDE.md
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HestiaDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun presenceConfigDao(): PresenceConfigDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao
    abstract fun pausedPlanningDao(): PausedPlanningDao

    companion object {
        const val NAME = "hestia.db"
    }
}
