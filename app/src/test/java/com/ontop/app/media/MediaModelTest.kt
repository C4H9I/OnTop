package com.ontop.app.media

import com.ontop.app.gallery.*
import com.ontop.app.text.*
import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class MediaModelTest {
    @Test fun legacyAttachmentsAndExplicitMediaRemainDifferentFromYouTube() {
        val audio = NoteMedia(offset = 0, kind = MediaKind.FILE, path = "attachments/abc.bin", mime = "audio/mpeg")
        val video = audio.copy(mime = "video/mp4")
        val youtube = NoteMedia(offset = 0, kind = MediaKind.YOUTUBE, video = YouTubeLink.parse("https://youtu.be/M7lc1UVf-VE"))
        assertTrue(audio.isAudio); assertFalse(audio.isLocalVideo)
        assertTrue(video.isLocalVideo); assertFalse(youtube.isLocalVideo); assertFalse(youtube.isAttachment)
        assertEquals(setOf("attachments/abc.bin"), RichDocument(media = listOf(audio, video, youtube)).resourcePaths())
    }
    @Test fun galleryRequestsOnlyTheMediaRelevantToItsDestination() {
        assertFalse(GalleryAccess.permissions(34, GalleryFilter.IMAGES).contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertFalse(GalleryAccess.permissions(34, GalleryFilter.VIDEOS).contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(GalleryAccess.permissions(34, GalleryFilter.VIDEOS).contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryAccess.permissions(32, GalleryFilter.VIDEOS))
    }
    @Test fun elapsedTimeHandlesMissingNegativeAndLongDurations() {
        assertEquals("0:00", mediaTime(-1)); assertEquals("1:05", mediaTime(65000)); assertEquals("61:01", mediaTime(3661000))
    }
}
