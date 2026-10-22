package com.ontop.app.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal data class GalleryAccess(val images: Boolean, val videos: Boolean, val selected: Boolean) {
    val canQueryImages get() = images || selected
    val canQueryVideos get() = videos || selected
    val any get() = canQueryImages || canQueryVideos
    val limited get() = any && !(images && videos)

    companion object {
        fun permissions(sdk: Int, filter: GalleryFilter = GalleryFilter.ALL): Array<String> = (when {
            sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }).filterNot {
            filter == GalleryFilter.IMAGES && it == Manifest.permission.READ_MEDIA_VIDEO ||
                filter == GalleryFilter.VIDEOS && it == Manifest.permission.READ_MEDIA_IMAGES
        }.toTypedArray()
        fun from(sdk: Int, granted: (String) -> Boolean): GalleryAccess = if (sdk >= 33) GalleryAccess(
            granted(Manifest.permission.READ_MEDIA_IMAGES), granted(Manifest.permission.READ_MEDIA_VIDEO),
            sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE).let { GalleryAccess(it, it, false) }

        fun current(context: Context) = from(Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
