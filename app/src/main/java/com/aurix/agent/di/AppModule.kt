package com.aurix.agent.di

import android.content.Context
import androidx.room.Room
import com.aurix.agent.core.mission.AppDatabase
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.tools.CalculatorTool
import com.aurix.agent.core.tools.FileEditTool
import com.aurix.agent.core.tools.FileListTool
import com.aurix.agent.core.tools.FileReadTool
import com.aurix.agent.core.tools.FileWriteTool
import com.aurix.agent.core.tools.ToolRegistry
import com.aurix.agent.core.tools.Workspace
import com.aurix.agent.core.tools.web.SafeFetcher
import com.aurix.agent.core.tools.web.SearchManager
import com.aurix.agent.core.tools.web.WebBrowserTool
import com.aurix.agent.core.tools.web.WebSearchTool
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
        Room.databaseBuilder(c, AppDatabase::class.java, "aurix.db")
            .fallbackToDestructiveMigrationFrom(1) // v1 (Phase 1 test data) only; later versions get real migrations
            .build()

    @Provides fun provideDao(db: AppDatabase): MissionDao = db.missionDao()

    @Provides @Singleton
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton @ApplicationScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun provideToolRegistry(search: SearchManager, fetcher: SafeFetcher, ws: Workspace): ToolRegistry = ToolRegistry(
        listOf(
            WebSearchTool(search), WebBrowserTool(fetcher),
            FileWriteTool(ws), FileReadTool(ws), FileEditTool(ws), FileListTool(ws),
            CalculatorTool(),
        )
    )
}
