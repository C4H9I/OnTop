package com.ontop.app.gallery

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class GalleryAccessTest {
    @Test fun permissionRequestsMatchPlatformVersions() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryAccess.permissions(26))
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryAccess.permissions(32))
        assertEquals(2, GalleryAccess.permissions(33).size)
        assertFalse(GalleryAccess.permissions(33).contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(3, GalleryAccess.permissions(34).size)
        assertTrue(GalleryAccess.permissions(37).contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }
    @Test fun deniedSelectedAndIndependentlyGrantedMediaTypes() {
        assertFalse(GalleryAccess.from(37) { false }.any)
        val partial = GalleryAccess.from(34) { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
        assertTrue(partial.limited); assertTrue(partial.canQueryImages); assertTrue(partial.canQueryVideos)
        val photosOnly = GalleryAccess.from(33) { it == Manifest.permission.READ_MEDIA_IMAGES }
        assertTrue(photosOnly.canQueryImages); assertFalse(photosOnly.canQueryVideos); assertTrue(photosOnly.limited)
        val videosOnly = GalleryAccess.from(37) { it == Manifest.permission.READ_MEDIA_VIDEO }
        assertFalse(videosOnly.canQueryImages); assertTrue(videosOnly.canQueryVideos)
        assertFalse(GalleryAccess.from(32) { true }.limited)
        assertFalse(GalleryAccess.from(37) { true }.limited)
    }
}
