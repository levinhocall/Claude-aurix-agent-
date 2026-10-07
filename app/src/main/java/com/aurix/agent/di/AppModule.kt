package com.aurix.agent.di

import android.content.Context
import androidx.room.Room
import com.aurix.agent.core.mission.AppDatabase
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.tools.CalculatorTool
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.approval.QuestionManager
import com.aurix.agent.core.memory.MemoryRepository
import com.aurix.agent.core.memory.MemoryTools
import com.aurix.agent.core.notifications.NotificationTools
import com.aurix.agent.core.tools.device.EmergencySosTool
import com.aurix.agent.core.tools.AskUserTool
import com.aurix.agent.core.tools.device.DeviceTools
import com.aurix.agent.core.tools.screen.ScreenTools
import com.aurix.agent.core.tools.storage.StorageTools
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
            .addMigrations(*com.aurix.agent.core.mission.Migrations.ALL)
            .build()

    @Provides fun provideDao(db: AppDatabase): MissionDao = db.missionDao()

    @Provides @Singleton
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(com.aurix.agent.core.net.CleartextGuard)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton @ApplicationScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun provideToolRegistry(@ApplicationContext ctx: Context, search: SearchManager, fetcher: SafeFetcher, ws: Workspace, questions: QuestionManager, memory: MemoryRepository, settings: AgentSettings): ToolRegistry = ToolRegistry(
        listOf(
            WebSearchTool(search), WebBrowserTool(fetcher),
            FileWriteTool(ws), FileReadTool(ws), FileEditTool(ws), FileListTool(ws),
            CalculatorTool(), AskUserTool(questions),
        ) + DeviceTools.all(ctx) + ScreenTools.all() + StorageTools.all(ctx, ws) + NotificationTools.all(settings) + MemoryTools.all(memory) + listOf(EmergencySosTool(ctx, memory))
    )
}
