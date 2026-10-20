package com.ontop.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotePercentagesStorageTest {
    @Test fun migrationPreservesRichNoteRelationsAndNullableProgressPersistsAfterReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val name = "percentages-${UUID.randomUUID()}.db"
        var db: OnTopDatabase? = null
        val rich = RichDocument("Текст 🧠", listOf(RichRange(0, 5, RichKind.BOLD)),
            listOf(NoteMedia("image", 0, MediaKind.IMAGE, path = "images/abc.jpg")))
        try {
            val schema = instrumentation.context.assets.open("com.ontop.app.data.OnTopDatabase/9.json").bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
            app.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { index ->
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) {
                        old.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table))
                    } }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                old.execSQL("INSERT INTO cards(id,title,description,size,createdAt,updatedAt,sortOrder,imagePath,formatting,importance,status) VALUES (1,'Мысль',?,'WIDE',10,20,3,'images/abc.jpg',?,75,'ARCHIVED')",
                    arrayOf(rich.text, RichTextCodec.encode(rich)))
                old.execSQL("INSERT INTO card_comments VALUES (2,1,'История',30)")
                old.execSQL("INSERT INTO tags VALUES (3,'Тема','тема',40)")
                old.execSQL("INSERT INTO card_tag_refs VALUES (1,3)")
                old.execSQL("INSERT INTO reminders(id,cardId,hour,minute,enabled,createdAt,updatedAt) VALUES (4,1,9,0,1,10,20)")
                old.version = 9
            }
            db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).addMigrations(OnTopDatabase.MIGRATION_9_10, OnTopDatabase.MIGRATION_10_11).build()
            val original = db.cardDao().findById(1)!!
            assertNull(original.progress)
            assertEquals(75, original.importance)
            assertEquals(rich, RichTextCodec.decode(original.description, original.formatting))
            val reminder = db.reminderDao().forCard(1)!!
            assertEquals("История", db.cardCommentDao().all().single().text)
            assertEquals(listOf(CardTagCrossRef(1, 3)), db.tagDao().allLinks())
            for (progress in listOf(0, 50, 100, null)) {
                val current = db!!
                assertEquals(1, current.cardDao().updatePercentages(1, 99, progress, 100))
                current.close()
                db = Room.databaseBuilder(app, OnTopDatabase::class.java, name).build()
                val reopened = db.cardDao().findById(1)!!
                assertEquals(original.copy(importance = 99, progress = progress, updatedAt = 100), reopened)
                assertEquals(reminder, db.reminderDao().forCard(1))
                assertEquals("История", db.cardCommentDao().all().single().text)
                assertEquals(listOf(CardTagCrossRef(1, 3)), db.tagDao().allLinks())
            }
            assertEquals(CardStatus.ARCHIVED, db.cardDao().findById(1)!!.status)
        } finally { db?.close(); app.deleteDatabase(name) }
    }
}
