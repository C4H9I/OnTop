package com.ontop.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CardEntity::class, CardCommentEntity::class, ReminderEntity::class, TagEntity::class, CardTagCrossRef::class, NoteInteractionEntity::class], version = 12, exportSchema = true)
abstract class OnTopDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun cardCommentDao(): CardCommentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun tagDao(): TagDao
    abstract fun noteInteractionDao(): NoteInteractionDao

    companion object {
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN noteType TEXT NOT NULL DEFAULT 'NOTE'")
                db.execSQL("ALTER TABLE cards ADD COLUMN checklistJson TEXT")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `note_interactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, `eventType` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, FOREIGN KEY(`noteId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_interactions_noteId` ON `note_interactions` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_interactions_noteId_timestamp` ON `note_interactions` (`noteId`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_interactions_eventType_id` ON `note_interactions` (`eventType`, `id`)")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN progress INTEGER")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalizedName` ON `tags` (`normalizedName`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `card_tag_refs` (`cardId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`cardId`, `tagId`), FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_tag_refs_cardId` ON `card_tag_refs` (`cardId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_tag_refs_tagId` ON `card_tag_refs` (`tagId`)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN importance INTEGER NOT NULL DEFAULT 50")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN formatting TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN mode TEXT NOT NULL DEFAULT 'TIMES'")
                db.execSQL("ALTER TABLE reminders ADD COLUMN daysMask INTEGER NOT NULL DEFAULT 127")
                db.execSQL("ALTER TABLE reminders ADD COLUMN times TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminders ADD COLUMN intervalHours INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE reminders ADD COLUMN windowStart INTEGER NOT NULL DEFAULT 540")
                db.execSQL("ALTER TABLE reminders ADD COLUMN windowEnd INTEGER NOT NULL DEFAULT 1260")
                db.execSQL("ALTER TABLE reminders ADD COLUMN quietStart INTEGER")
                db.execSQL("ALTER TABLE reminders ADD COLUMN quietEnd INTEGER")
                db.execSQL("ALTER TABLE reminders ADD COLUMN pausedUntil INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `nextAt` INTEGER, `snoozeAt` INTEGER, FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reminders_cardId` ON `reminders` (`cardId`)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cards` ADD COLUMN `imagePath` TEXT")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `card_comments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_comments_cardId` ON `card_comments` (`cardId`)")
            }
        }
        @Volatile private var instance: OnTopDatabase? = null

        fun getInstance(context: Context): OnTopDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OnTopDatabase::class.java,
                "ontop.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12).build().also { instance = it }
        }
    }
}
