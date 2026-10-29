package kapoue.hestia.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kapoue.hestia.data.local.dao.DeviceDao
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.data.prefs.EncryptedPasswordVault
import kapoue.hestia.data.prefs.PasswordVault
import kapoue.hestia.data.repository.DevicePasswords
import kapoue.hestia.data.repository.pinsByAddress
import kapoue.hestia.data.rpc.SavedPasswordSource
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

    @Provides
    @Singleton
    fun providePasswordVault(@ApplicationContext context: Context): PasswordVault =
        EncryptedPasswordVault(context)

    @Provides
    @Singleton
    fun provideDevicePasswords(deviceDao: DeviceDao, vault: PasswordVault): DevicePasswords =
        DevicePasswords({ deviceDao.getAllOnce() }, vault)

    /** Même instance que [DevicePasswords] : le registre d'accès et l'écran partagent le même coffre. */
    @Provides
    fun provideSavedPasswordSource(passwords: DevicePasswords): SavedPasswordSource = passwords
}
