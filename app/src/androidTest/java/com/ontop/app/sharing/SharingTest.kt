package com.ontop.app.sharing

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ontop.app.data.*
import com.ontop.app.text.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SharingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = CardImageStore(context)
    private fun image(color: Int): String {
        val path = "images/${UUID.randomUUID()}.jpg"
        val file = store.resolve(path).apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
        return path
    }
    private fun card(path: String? = null) = CardEntity(7654321, "Важная мысль", "Текст 🧠\nДругой абзац", createdAt = 1,
        updatedAt = 2, sortOrder = 99, imagePath = path, importance = 100)

    @Test fun implicitShareDiscoveryAcceptsTextAndImagesButNotMultipleOrPdf() {
        fun matches(action: String, mime: String) = context.packageManager.queryIntentActivities(
            Intent(action).setType(mime).setPackage(context.packageName).addCategory(Intent.CATEGORY_DEFAULT),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name == "com.ontop.app.MainActivity" }
        assertTrue(matches(Intent.ACTION_SEND, "text/plain"))
        assertTrue(matches(Intent.ACTION_SEND, "image/jpeg"))
        assertTrue(matches(Intent.ACTION_SEND, "image/png"))
        assertFalse(matches(Intent.ACTION_SEND_MULTIPLE, "image/jpeg"))
        assertFalse(matches(Intent.ACTION_SEND, "application/pdf"))
    }

    @Test fun incomingTextSubjectClipDataAndUnsupportedInputs() {
        val link = "https://example.com/article?x=1"
        val parsed = readIncomingShare(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Статья").putExtra(Intent.EXTRA_TEXT, link))!!
        assertEquals("Статья", parsed.title); assertEquals(link, parsed.text); assertNull(parsed.imageUri)
        val clip = Intent(Intent.ACTION_SEND).setType("text/plain").apply { clipData = ClipData.newPlainText("text", "Мысль\nПродолжение") }
        assertEquals("Мысль", readIncomingShare(clip)!!.title)
        assertEquals("Мысль\nПродолжение", readIncomingShare(clip)!!.text)
        clip.clipData = ClipData.newRawUri("link", Uri.parse(link))
        assertEquals(link, readIncomingShare(clip)!!.text)
        assertNull(readIncomingShare(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")))
        assertThrows(IllegalArgumentException::class.java) { readIncomingShare(Intent(Intent.ACTION_SEND).setType("application/pdf")) }
        assertThrows(IllegalArgumentException::class.java) { readIncomingShare(Intent(Intent.ACTION_SEND).setType("text/plain")) }
    }

    @Test fun incomingImageUsesStreamOrClipDataDeduplicatesAndRejectsMultipleOrPrivateFiles() {
        val uri = Uri.parse("content://gallery/image/1")
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Подпись")
        intent.clipData = ClipData.newRawUri("image", uri)
        assertEquals(uri.toString(), readIncomingShare(intent)!!.imageUri)
        intent.removeExtra(Intent.EXTRA_STREAM)
        assertEquals(uri.toString(), readIncomingShare(intent)!!.imageUri)
        intent.clipData!!.addItem(ClipData.Item(Uri.parse("content://gallery/image/2")))
        assertThrows(IllegalArgumentException::class.java) { readIncomingShare(intent) }
        val privateFile = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/user/0/private.jpg"))
        assertThrows(IllegalArgumentException::class.java) { readIncomingShare(privateFile) }
    }

    @Test fun inboxKeepsOrderAcrossSavedStateAndConsumesOnlyOneDelivery() {
        val first = IncomingShare(title = "A", text = "one")
        val second = IncomingShare(title = "B", text = "two")
        val inbox = ShareInbox(SavedStateHandle())
        inbox.enqueue(first); inbox.enqueue(second)
        val restored = ShareInbox(SavedStateHandle(mapOf("incoming_shares" to ArrayList(inbox.queue.value))))
        restored.consumed(first.id)
        assertEquals(listOf(second), restored.queue.value)
        restored.consumed(first.id)
        assertEquals(listOf(second), restored.queue.value)
    }

    @Test fun outboundTextKeepsParagraphsAndVideoLinksWithoutMetadata() = runBlocking {
        val original = card()
        val source = "До\nПосле"
        val video = NoteMedia("video", 3, MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?t=42"))
        val formatted = original.copy(description = source,
            formatting = RichTextCodec.encode(RichDocument(source, listOf(RichRange(0, 2, RichKind.BOLD)), listOf(video))))
        val prepared = OutgoingShare.prepare(context, formatted)
        assertEquals(Intent.ACTION_SEND, prepared.intent.action)
        assertEquals("text/plain", prepared.intent.type)
        assertEquals("Важная мысль\n\nДо\nhttps://youtu.be/dQw4w9WgXcQ?t=42\nПосле", prepared.intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), prepared.intent.extras!!.keySet())
        assertFalse(sharedNoteText(formatted).contains("7654321"))
        assertEquals(original, card())
        val chooser = Intent.createChooser(prepared.intent, "Поделиться заметкой")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
    }

    @Test fun outboundImageIsReadableCacheCopyWithGrantAndNarrowProviderScope() = runBlocking {
        val path = image(Color.GREEN)
        var uri: Uri? = null
        try {
            val original = card(path)
            val prepared = OutgoingShare.prepare(context, original)
            @Suppress("DEPRECATION") val attachment = prepared.intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            uri = attachment
            assertEquals("content", attachment.scheme)
            assertEquals("${context.packageName}.shares", attachment.authority)
            assertEquals("image/jpeg", prepared.intent.type)
            assertTrue(prepared.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(attachment, prepared.intent.clipData!!.getItemAt(0).uri)
            assertArrayEquals(store.resolve(path).readBytes(), context.contentResolver.openInputStream(attachment)!!.use { it.readBytes() })
            assertEquals(sharedNoteText(original), prepared.intent.getStringExtra(Intent.EXTRA_TEXT))
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.shares", File(context.filesDir, "private.db"))
            }
            // Deleting/replacing the source later cannot invalidate the already shared snapshot.
            store.delete(path)
            assertTrue(context.contentResolver.openInputStream(attachment)!!.use { it.read() } >= 0)
        } finally {
            store.delete(path)
            uri?.lastPathSegment?.let { File(context.cacheDir, "shared-notes/$it").delete() }
        }
    }

    @Test fun inlineFallbackAndMissingCoverRemainSafe() = runBlocking {
        val first = image(Color.BLUE)
        val cover = image(Color.RED)
        val copies = mutableListOf<String>()
        try {
            val formatted = card().copy(formatting = RichTextCodec.encode(RichDocument(card().description,
                media = listOf(NoteMedia(offset = 0, kind = MediaKind.IMAGE, path = first)))))
            for ((note, expected) in listOf(formatted to first, formatted.copy(imagePath = cover) to cover)) {
                val result = OutgoingShare.prepare(context, note)
                @Suppress("DEPRECATION") val uri = result.intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                copies += uri.lastPathSegment!!
                assertArrayEquals(store.resolve(expected).readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
            store.delete(cover)
            val unavailable = OutgoingShare.prepare(context, formatted.copy(imagePath = cover))
            assertTrue(unavailable.imageUnavailable)
            assertEquals("text/plain", unavailable.intent.type)
            assertFalse(unavailable.intent.hasExtra(Intent.EXTRA_STREAM))
        } finally {
            store.delete(first); store.delete(cover)
            copies.forEach { File(context.cacheDir, "shared-notes/$it").delete() }
        }
    }

    @Test fun importedImageSurvivesSourceRemovalAndDatabaseReopen() = runBlocking<Unit> {
        val source = image(Color.MAGENTA)
        val documents = File(context.cacheDir, "backup-test-documents").apply { mkdirs() }
        val external = File(documents, "share-${UUID.randomUUID()}.jpg")
        store.resolve(source).copyTo(external)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.backupfixtures", external)
        val name = "share-persistence-${UUID.randomUUID()}.db"
        var local: String? = null
        var db: OnTopDatabase? = null
        try {
            local = store.import(uri)
            assertTrue(local.startsWith("images/")); assertFalse(local.contains("content:"))
            external.delete(); store.delete(source)
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            db.cardDao().insert(card(local))
            db.close()
            db = Room.databaseBuilder(context, OnTopDatabase::class.java, name).build()
            val restored = db.cardDao().findById(card().id)!!
            assertEquals(local, restored.imagePath)
            store.load(restored.imagePath!!, 100).also { assertEquals(30, it.width); it.recycle() }
        } finally { db?.close(); context.deleteDatabase(name); store.delete(source); store.delete(local); external.delete() }
    }
}
