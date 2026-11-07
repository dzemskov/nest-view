package app.nestview

import android.app.Application
import app.nestview.core.sdm.DefaultSdmConfig
import app.nestview.core.sdm.SdmConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@HiltAndroidApp
class NestViewApplication : Application()

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Device Access settings baked in from local.properties (can be overridden in the app). */
    @Provides
    @Singleton
    fun defaultSdmConfig(): DefaultSdmConfig = DefaultSdmConfig(
        SdmConfig(
            projectId = BuildConfig.SDM_PROJECT_ID,
            clientId = BuildConfig.OAUTH_CLIENT_ID,
            clientSecret = BuildConfig.OAUTH_CLIENT_SECRET.ifBlank { null },
            redirectUri = BuildConfig.OAUTH_REDIRECT_URI,
        ),
    )
}
