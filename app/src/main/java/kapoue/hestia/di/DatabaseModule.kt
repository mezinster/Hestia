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
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.DiagnosticLogDao
import kapoue.hestia.data.local.dao.PresenceConfigDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HestiaDatabase =
        Room.databaseBuilder(context, HestiaDatabase::class.java, HestiaDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .build()

    @Provides
    fun provideDeviceDao(db: HestiaDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun providePresenceConfigDao(db: HestiaDatabase): PresenceConfigDao = db.presenceConfigDao()

    @Provides
    fun provideDiagnosticLogDao(db: HestiaDatabase): DiagnosticLogDao = db.diagnosticLogDao()
}
