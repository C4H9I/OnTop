package com.ontop.app.text

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.delay

internal const val PLAYER_ORIGIN = "https://com.ontop.app"
internal fun playerHtml(video: YouTubeLink): String {
    require(YouTubeLink.parse(video.watchUrl) != null)
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin"><style>html,body{margin:0;background:#000;height:100%}#player{width:100%;height:100%}</style></head>
        <body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script><script>
        var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'${video.id}',
        playerVars:{playsinline:1,start:${video.seconds},origin:'$PLAYER_ORIGIN'},events:{
        onReady:function(){console.log('ONTOP_YT_READY')},
        onStateChange:function(e){if(e.data===1)console.log('ONTOP_YT_PLAYING')},
        onError:function(e){console.log('ONTOP_YT_ERROR_'+e.data)}}});}</script></body></html>""".trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubePlayerDialog(video: YouTubeLink, onClose: () -> Unit) {
    val context = LocalContext.current
    var status by remember(video) { mutableStateOf("Загрузка YouTube…") }
    var ready by remember(video) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val web = remember(video, retry) { runCatching {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setGeolocationEnabled(false)
            settings.safeBrowsingEnabled = true
            settings.setSupportMultipleWindows(false)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    when {
                        message.message() == "ONTOP_YT_READY" -> { ready = true; status = "Нажмите ▶ в проигрывателе" }
                        message.message() == "ONTOP_YT_PLAYING" -> { ready = true; status = "Воспроизведение" }
                        message.message().startsWith("ONTOP_YT_ERROR_") -> { ready = true; status = "YouTube не разрешил воспроизведение здесь. Откройте ссылку снаружи." }
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (request.isForMainFrame) return uri.toString() != "$PLAYER_ORIGIN/"
                    return uri.scheme != "https" || uri.host !in setOf("www.youtube.com", "youtube.com", "www.youtube-nocookie.com")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Statistics/advertising resources can fail while the video is playing.
                    // Only failure of the page, API script or embed itself means no player.
                    if (request.isForMainFrame || request.url.path == "/iframe_api" || request.url.path?.startsWith("/embed/") == true)
                        status = "Нет соединения с YouTube. Проверьте интернет или откройте ссылку позже."
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); status = "Не удалось безопасно подключиться к YouTube."
                }
            }
            loadDataWithBaseURL("$PLAYER_ORIGIN/", playerHtml(video), "text/html", "UTF-8", null)
        }
    }.getOrNull() }
    LaunchedEffect(web) {
        delay(20_000)
        if (!ready) status = "YouTube не отвечает. Проверьте интернет или откройте ссылку позже."
    }
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { web?.evaluateJavascript("if(window.player)player.pauseVideo()", null); web?.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) web?.onResume()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); web?.stopLoading(); web?.destroy() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).safeDrawingPadding().padding(16.dp)) {
                Text("YouTube", style = MaterialTheme.typography.titleLarge)
                if (web != null) key(web) { AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("youtube-player")) }
                Text(if (web == null) "Встроенный проигрыватель недоступен" else status, modifier = Modifier.testTag("youtube-status"))
                TextButton(onClick = { ready = false; status = "Загрузка YouTube…"; retry++ }) { Text("Повторить подключение") }
                OpenVideoLink(video)
                TextButton(onClick = onClose) { Text("Закрыть видео") }
            }
        }
    }
}
