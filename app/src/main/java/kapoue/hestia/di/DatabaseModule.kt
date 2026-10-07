package kapoue.hestia.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kapoue.hestia.data.local.HestiaDatabase
import kapoue.hestia.data.local.MIGRATION_1_2
import kapoue.hestia.data.local.MIGRATION_2_3
import kapoue.hestia.data.local.MIGRATION_3_4
import kapoue.hestia.data.local.MIGRATION_4_5
import kapoue.hestia.data.local.MIGRATION_5_6
import kapoue.hestia.data.local.MIGRATION_6_7
import kapoue.hestia.data.local.MIGRATION_7_8
import kapoue.hestia.data.local.MIGRATION_8_9
import kapoue.hestia.data.local.MIGRATION_9_10
import kapoue.hestia.data.local.MIGRATION_10_11
import kapoue.hestia.data.local.MIGRATION_11_12
import kapoue.hestia.data.local.MIGRATION_12_14
import kapoue.hestia.data.local.MIGRATION_14_15
import kapoue.hestia.data.local.MIGRATION_15_16
import kapoue.hestia.data.local.MIGRATION_16_17
import kapoue.hestia.data.local.MIGRATION_17_18
import kapoue.hestia.data.local.MIGRATION_18_19
import kapoue.hestia.data.local.MIGRATION_19_20
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.DiagnosticLogDao
import kapoue.hestia.data.local.dao.PausedCoverEventDao
import kapoue.hestia.data.local.dao.PausedPlanningDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HestiaDatabase =
        Room.databaseBuilder(context, HestiaDatabase::class.java, HestiaDatabase.NAME)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12, MIGRATION_12_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
            )
            .build()

    @Provides
    fun provideDeviceDao(db: HestiaDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun provideDiagnosticLogDao(db: HestiaDatabase): DiagnosticLogDao = db.diagnosticLogDao()

    @Provides
    fun providePausedPlanningDao(db: HestiaDatabase): PausedPlanningDao = db.pausedPlanningDao()

    @Provides
    fun providePausedCoverEventDao(db: HestiaDatabase): PausedCoverEventDao = db.pausedCoverEventDao()
}
