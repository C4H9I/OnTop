package com.ontop.app.media

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.backup.*
import com.ontop.app.data.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class LocalMediaStorageTest {
    @Test fun audioVideoAndLargeVideoSurviveReopenAndFullRestoreWithoutRecopyOnRead() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "shared-notes/media-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "multimedia-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
        val images = CardImageStore(context)
        val owned = mutableSetOf<String>()
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        try {
            val blocks = listOf("sample.mp3", "sample.mp4").map { filename ->
                val source = File(dir, filename)
                instrumentation.context.assets.open("media/$filename").use { input -> source.outputStream().use { input.copyTo(it) } }
                AttachmentStore(context).import(FileProvider.getUriForFile(context, "${context.packageName}.shares", source))
                    .also { owned.add(it.path!!); source.delete() }
            }
            assertEquals(MediaKind.AUDIO, blocks[0].kind); assertEquals(MediaKind.LOCAL_VIDEO, blocks[1].kind)
            assertTrue(blocks.all { (it.durationMillis ?: 0) > 0 })
            val large = File(dir, "large.mp4")
            large.outputStream().use { output ->
                images.resolve(blocks[1].path!!).inputStream().use { it.copyTo(output) }
                val buffer = ByteArray(65536).apply { java.util.Random(42).nextBytes(this) }
                repeat(320) { output.write(buffer) }
            }
            val largeBlock = AttachmentStore(context).import(FileProvider.getUriForFile(context, "${context.packageName}.shares", large))
            owned.add(largeBlock.path!!)
            assertTrue(largeBlock.bytes > 16L * 1024 * 1024)
            val all = blocks + largeBlock
            val document = RichDocument("media", media = all)
            val id = db.cardDao().insert(CardEntity(title = "Media", description = document.text, formatting = RichTextCodec.encode(document),
                createdAt = 1, updatedAt = 1, sortOrder = 0))
            val signatures = all.associate { it.name!! to hash(images.resolve(it.path!!)) }
            val beforeFiles = File(context.filesDir, "attachments").list()?.toSet()
            db.close(); db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            val reopened = db.cardDao().findById(id)!!
            assertEquals(document, RichTextCodec.decode(reopened.description, reopened.formatting))
            assertEquals(beforeFiles, File(context.filesDir, "attachments").list()?.toSet())
            val legacy = RichTextCodec.encode(document.copy(media = listOf(blocks[0].copy(kind = MediaKind.FILE))))!!
            assertTrue(RichTextCodec.decode(document.text, legacy).media.single().isAudio)
            val archive = File(dir, "media.ontop")
            val manager = BackupManager(context, db, root = File(dir, "backup-work"), refreshWidgets = {})
            manager.create { archive.outputStream() }; assertNull(manager.state.value.error)
            manager.prepare { archive.inputStream() }; assertNull(manager.state.value.error)
            manager.restore(); assertNull(manager.state.value.error)
            val restored = db.cardDao().allCards().single()
            val restoredBlocks = RichTextCodec.decode(restored.description, restored.formatting).media
            assertEquals(all.map { it.kind }, restoredBlocks.map { it.kind })
            restoredBlocks.forEach {
                owned.add(it.path!!)
                assertEquals(signatures[it.name], hash(images.resolve(it.path)))
                assertEquals(it.bytes, images.resolve(it.path).length())
                assertTrue(it.path !in all.map { old -> old.path })
            }
        } finally {
            db.close(); context.deleteDatabase(name)
            owned.forEach { images.delete(it) }; dir.deleteRecursively()
        }
    }
}
