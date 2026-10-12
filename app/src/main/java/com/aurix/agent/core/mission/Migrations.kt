package com.aurix.agent.core.mission

import androidx.room.migration.Migration

/** Register real Room migrations here (v2 -> v3 ...) so mission data is never wiped. */
object Migrations {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `kind` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `uses` INTEGER NOT NULL)")
        }
    }

    /** v3 -> v4: durable checkpoints, side-effect journal, verification results, model-call diagnostics. Additive only. */
    val SQL_3_4: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `mission_checkpoints` (`missionId` TEXT NOT NULL, `version` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `planHash` TEXT NOT NULL, `body` TEXT NOT NULL, PRIMARY KEY(`missionId`))",
        "CREATE TABLE IF NOT EXISTS `side_effects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `missionId` TEXT NOT NULL, `stepIdx` INTEGER NOT NULL, `tool` TEXT NOT NULL, `inputHash` TEXT NOT NULL, `summary` TEXT NOT NULL, `state` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `note` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_side_effects_missionId` ON `side_effects` (`missionId`)",
        "CREATE TABLE IF NOT EXISTS `verifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `missionId` TEXT NOT NULL, `stepIdx` INTEGER NOT NULL, `state` TEXT NOT NULL, `evidence` TEXT NOT NULL, `ts` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_verifications_missionId` ON `verifications` (`missionId`)",
        "CREATE TABLE IF NOT EXISTS `model_calls` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `missionId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `purpose` TEXT NOT NULL, `model` TEXT NOT NULL, `latencyMs` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `errorType` TEXT, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `tokensReported` INTEGER NOT NULL, `costUsdEstimate` REAL, `routeNote` TEXT NOT NULL, `retried` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_model_calls_ts` ON `model_calls` (`ts`)",
        "CREATE INDEX IF NOT EXISTS `index_model_calls_missionId` ON `model_calls` (`missionId`)",
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { SQL_3_4.forEach { db.execSQL(it) } }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4)
}
