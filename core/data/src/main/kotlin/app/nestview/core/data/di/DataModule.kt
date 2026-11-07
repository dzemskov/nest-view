package app.nestview.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.nestview.core.data.AppState
import app.nestview.core.data.AppStateSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun appStateStore(@ApplicationContext context: Context): DataStore<AppState> = DataStoreFactory.create(
        serializer = AppStateSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppState() },
        produceFile = { context.dataStoreFile("app_state.json") },
    )
}
