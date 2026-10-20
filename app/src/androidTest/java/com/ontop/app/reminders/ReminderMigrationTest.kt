package com.ontop.app.reminders

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReminderMigrationTest {
    @Test fun migrationThreeToFourPreservesAllDataAndEnforcesOneRuleAndCascade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reminder-migration-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        try {
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.ontop.app.data.OnTopDatabase/3.json").bufferedReader().use {
                    JSONObject(it.readText()).getJSONObject("database")
                }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j)
                        .getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO cards VALUES (7, 'Мысль', 'Описание', 'WIDE', 100, 200, 3, 'images/abc.jpg')")
                old.execSQL("INSERT INTO card_comments VALUES (9, 7, 'Размышление', 300)")
                old.version = 3
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name)
                .addMigrations(OnTopDatabase.MIGRATION_3_4, OnTopDatabase.MIGRATION_4_5, OnTopDatabase.MIGRATION_5_6, OnTopDatabase.MIGRATION_6_7, OnTopDatabase.MIGRATION_7_8, OnTopDatabase.MIGRATION_8_9, OnTopDatabase.MIGRATION_9_10, OnTopDatabase.MIGRATION_10_11).build()
            val card = db.cardDao().findById(7)!!
            assertEquals(CardEntity(7, "Мысль", "Описание", CardSize.WIDE, 100, 200, 3, "images/abc.jpg"), card)
            assertEquals(CardCommentEntity(9, 7, "Размышление", 300), db.cardCommentDao().observeComments(7).first().single())
            assertTrue(db.reminderDao().all().isEmpty())
            val rule = ReminderEntity(cardId = 7, hour = 9, minute = 30, enabled = true, createdAt = 1, updatedAt = 1)
            val id = db.reminderDao().insert(rule)
            try { db.reminderDao().insert(rule); fail("Duplicate card rule accepted") }
            catch (_: android.database.sqlite.SQLiteConstraintException) { }
            try { db.reminderDao().insert(rule.copy(cardId = 999)); fail("Missing parent accepted") }
            catch (_: android.database.sqlite.SQLiteConstraintException) { }
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals(id, db.reminderDao().forCard(7)!!.id)
            assertEquals(card, db.cardDao().findById(7))
            db.cardDao().delete(card)
            assertTrue(db.reminderDao().all().isEmpty())
            assertTrue(db.cardCommentDao().observeComments(7).first().isEmpty())
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
