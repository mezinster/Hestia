package kapoue.hestia.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.repository.pinsByAddress
import kapoue.hestia.data.rpc.SavedPinSource
import javax.inject.Singleton

/** Accès HTTPS : les épingles enregistrées sont lues directement en base. */
@Module
@InstallIn(SingletonComponent::class)
object AccessModule {

    @Provides
    @Singleton
    fun provideSavedPinSource(deviceDao: DeviceDao): SavedPinSource =
        SavedPinSource { pinsByAddress(deviceDao.getAllOnce()) }
}
