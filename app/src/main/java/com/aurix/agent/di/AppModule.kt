package com.aurix.agent.di

import android.content.Context
import androidx.room.Room
import com.aurix.agent.core.mission.AppDatabase
import com.aurix.agent.core.mission.MissionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDb(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "aurix.db").build() // no destructive migration: mission state must survive

    @Provides fun provideDao(db: AppDatabase): MissionDao = db.missionDao()

    @Provides @Singleton
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton @ApplicationScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
