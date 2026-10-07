package com.aurix.agent.core.mission

import androidx.room.migration.Migration

/** Register real Room migrations here (v2 -> v3 ...) so mission data is never wiped. */
object Migrations {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `kind` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `uses` INTEGER NOT NULL)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
