package com.ontop.app.text

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RichTextMigrationTest {
    @Test fun migrationFiveToSixKeepsPlainTextImagesCommentsAndAdvancedRemindersAndPersistsStyles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "rich-migration-${System.nanoTime()}.db"
        var db: OnTopDatabase? = null
        val plain = "  Прежняя мысль\nВторая строка\n"
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/5.json").bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO cards VALUES (7, 'Заголовок', ?, 'WIDE', 100, 200, 3, 'images/abc.jpg')", arrayOf(plain))
                old.execSQL("INSERT INTO card_comments VALUES (9, 7, 'История', 300)")
                old.execSQL("INSERT INTO reminders(id, cardId, hour, minute, enabled, createdAt, updatedAt, nextAt, snoozeAt, daysMask, times, quietStart, quietEnd) VALUES (11,7,9,0,1,400,500,600,700,21,'540,1080',1320,480)")
                old.version = 5
            }
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).addMigrations(OnTopDatabase.MIGRATION_5_6).build()
            val oldCard = db.cardDao().findById(7)!!
            assertEquals(plain, oldCard.description)
            assertNull(oldCard.formatting)
            assertEquals(CardSize.WIDE, oldCard.size)
            assertEquals("images/abc.jpg", oldCard.imagePath)
            assertEquals("История", db.cardCommentDao().observeComments(7).first().single().text)
            val rule = db.reminderDao().forCard(7)!!
            assertEquals("540,1080", rule.times)
            assertEquals(21, rule.daysMask)
            assertEquals(600L, rule.nextAt)
            assertEquals(700L, rule.snoozeAt)
            val document = RichTextCodec.decode(plain, null).toggle(RichKind.BOLD, 2, 9).toggle(RichKind.ITALIC, 2, 9)
                .toggle(RichKind.BULLET, 17, 17)
            val saved = oldCard.copy(formatting = RichTextCodec.encode(document))
            db.cardDao().update(saved)
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            assertEquals(saved, db.cardDao().findById(7))
            assertEquals(document, RichTextCodec.decode(db.cardDao().findById(7)!!.description, db.cardDao().findById(7)!!.formatting))
            assertEquals(rule, db.reminderDao().forCard(7))
        } finally { db?.close(); context.deleteDatabase(name) }
    }
    @Test fun codecIsVersionedCanonicalAndMalformedFormattingNeverChangesText() {
        val doc = RichDocument("Мысль 🌿\nПринцип").toggle(RichKind.BOLD, 0, 5).toggle(RichKind.COLOR, 0, 5, "forest")
        assertEquals(doc, RichTextCodec.decode(doc.text, RichTextCodec.encode(doc)))
        assertNull(RichTextCodec.encode(RichDocument("Просто текст")))
        listOf(null, "", "not json", "{\"v\":99}", "{\"v\":1,\"ranges\":[{\"s\":-1,\"e\":999,\"k\":\"BOLD\"}]}").forEach {
            assertEquals(RichDocument(doc.text), RichTextCodec.decode(doc.text, it))
        }
    }
}
