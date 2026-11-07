package app.nestview.core.sdm.di

import app.nestview.core.model.CameraStreamApi
import app.nestview.core.sdm.SdmCameraStreamApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SdmBindings {
    @Binds
    abstract fun cameraStreamApi(impl: SdmCameraStreamApi): CameraStreamApi
}

@Module
@InstallIn(SingletonComponent::class)
object SdmProviders {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}
