
package com.wakemessenger.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, UserEntity::class, SettingEntity::class],
    version = 2,
    exportSchema = true // схемы лежат в app/schemas и нужны для теста миграций
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun users(): UserDao
    abstract fun settings(): SettingsDao

    companion object {
        /** v1 -> v2: приоритет сообщения. Существующие строки получают normal. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal'")
            }
        }

        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "wakeup.db")
                // Раньше здесь стоял fallbackToDestructiveMigration(): любое повышение версии стирало
                // историю, офлайн-очередь и chat_settings (api_host, device_id). Теперь только миграции.
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}


