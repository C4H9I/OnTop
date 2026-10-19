package com.ontop.app

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class AppVersionInfo(
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val buildId: String,
    val builtAt: Instant,
    val buildType: String
) {
    val isDebug: Boolean get() = buildType == "debug"
    val typeLabel: String get() = if (isDebug) "Debug" else "Release"
    val shortId: String get() = buildId.take(8)
    val buildDate: String get() = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss XXX")
        .withZone(ZoneId.systemDefault()).format(builtAt)
    fun clipboardText() = "$name\nversionName: $versionName\nversionCode: $versionCode\n" +
        "Сборка: $buildId\nДата сборки: $buildDate\nТип: $typeLabel"

    companion object {
        @Suppress("DEPRECATION")
        suspend fun load(context: Context): AppVersionInfo = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            val installed = manager.getPackageInfo(context.packageName, 0)
            val metadata = context.assets.open("ontop_build.json").bufferedReader().use { JSONObject(it.readText()) }
            AppVersionInfo(context.getString(R.string.app_name), installed.versionName.orEmpty(),
                PackageInfoCompat.getLongVersionCode(installed), metadata.getString("buildId"),
                Instant.parse(metadata.getString("builtAt")), metadata.getString("buildType"))
        }
    }
}
