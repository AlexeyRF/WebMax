package ru.alexeyrf.webmax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val colorUpdateRunnable = object : Runnable {
        override fun run() {
            updateColorsFromWebView()
            handler.postDelayed(this, 300)
        }
    }

    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val clipData = data.clipData
                val uri = data.data
                if (clipData != null) {
                    val uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    fileChooserCallback?.onReceiveValue(uris)
                } else if (uri != null) {
                    fileChooserCallback?.onReceiveValue(arrayOf(uri))
                } else {
                    fileChooserCallback?.onReceiveValue(null)
                }
            } else {
                fileChooserCallback?.onReceiveValue(null)
            }
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    // Лаунчер для запроса рантайм-разрешений Android (для камеры, микрофона и геолокации)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        // 1. Обработка разрешений для камеры и микрофона в WebView
        pendingPermissionRequest?.let { request ->
            val grantedList = mutableListOf<String>()
            
            val isCameraGranted = permissionsResult[Manifest.permission.CAMERA] == true || 
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                
            val isAudioGranted = permissionsResult[Manifest.permission.RECORD_AUDIO] == true || 
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            for (resource in request.resources) {
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        if (isCameraGranted) grantedList.add(resource)
                    }
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        if (isAudioGranted) grantedList.add(resource)
                    }
                    else -> grantedList.add(resource)
                }
            }

            if (grantedList.isNotEmpty()) {
                request.grant(grantedList.toTypedArray())
            } else {
                request.deny()
            }
            pendingPermissionRequest = null
        }

        // 2. Обработка разрешений для геолокации
        pendingGeolocationCallback?.let { callback ->
            val isLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            callback.invoke(pendingGeolocationOrigin, isLocationGranted, false)
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        checkBatteryOptimizations()
        
        if (!BuildConfig.IS_FCM) {
            val workRequest = PeriodicWorkRequestBuilder<BackgroundWebViewWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "WebMaxBackgroundCheck",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        // Разрешаем изменять цвет статус-бара
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()

        // Загружаем страницу, если активность создается впервые
        if (savedInstanceState == null) {
            checkVpnAndLoadUrl()
        }

        // Обработка кнопки "Назад" (современный способ)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun checkVpnAndLoadUrl() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var hasVpn = false
        var wifiNetwork: Network? = null
        var cellNetwork: Network? = null

        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                hasVpn = true
            } else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    wifiNetwork = network
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    cellNetwork = network
                }
            }
        }

        val underlyingNetwork = wifiNetwork ?: cellNetwork

        if (hasVpn) {
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val vpnChoice = prefs.getString("vpn_choice", "unset")

            when (vpnChoice) {
                "bypass" -> {
                    if (underlyingNetwork != null) {
                        cm.bindProcessToNetwork(underlyingNetwork)
                    } else {
                        cm.bindProcessToNetwork(null)
                    }
                    webView.loadUrl("https://web.max.ru")
                }
                "use_vpn" -> {
                    cm.bindProcessToNetwork(null)
                    webView.loadUrl("https://web.max.ru")
                }
                else -> {
                    showVpnDialog(cm, underlyingNetwork, prefs)
                }
            }
        } else {
            cm.bindProcessToNetwork(null)
            webView.loadUrl("https://web.max.ru")
        }
    }

    private fun showVpnDialog(cm: ConnectivityManager, underlyingNetwork: Network?, prefs: SharedPreferences) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Обнаружен VPN")
        builder.setMessage("Как загрузить сайт?\n\n" +
                "• Через VPN (внимание: может раскрыть выходной узел)\n" +
                "• Напрямую (в обход VPN)")
        
        builder.setPositiveButton("Через VPN") { _, _ ->
            prefs.edit().putString("vpn_choice", "use_vpn").apply()
            cm.bindProcessToNetwork(null)
            webView.loadUrl("https://web.max.ru")
        }
        
        builder.setNegativeButton("В обход VPN") { _, _ ->
            prefs.edit().putString("vpn_choice", "bypass").apply()
            if (underlyingNetwork != null) {
                cm.bindProcessToNetwork(underlyingNetwork)
            } else {
                cm.bindProcessToNetwork(null)
            }
            webView.loadUrl("https://web.max.ru")
        }
        
        builder.setCancelable(false)
        builder.show()
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings

        // Включаем JavaScript (нужно для большинства современных сайтов)
        settings.javaScriptEnabled = true

        // Разрешаем воспроизведение медиа без жестов пользователя (важно для WebRTC/Media)
        settings.mediaPlaybackRequiresUserGesture = false

        // --- СОХРАНЕНИЕ ДАННЫХ ДЛЯ ВХОДА И СЕССИЙ ---
        settings.domStorageEnabled = true // Включает LocalStorage (сохраняет токены и данные)
        settings.databaseEnabled = true   // Включает Web SQL Database

        // Настройка кэширования
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Настройка Cookie для сохранения сессий (cookies)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Разрешаем смешанный контент (HTTP и HTTPS одновременно)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        if (BuildConfig.IS_FCM) {
            webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
        }

        // Указываем WebViewClient, чтобы ссылки открывались внутри приложения, а не в браузере
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                return handleUrl(request.url.toString(), view)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrl(url, view)
            }

            private fun handleUrl(url: String, view: WebView): Boolean {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    view.context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        // WebChromeClient обрабатывает события вроде вызовов JS, прогресса загрузки и разрешений
        webView.webChromeClient = object : WebChromeClient() {

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                handlePageTitleChange(title)
            }

            // Запрос разрешений (Камера, Микрофон) со стороны веб-страницы
            override fun onPermissionRequest(request: PermissionRequest) {
                pendingPermissionRequest = request
                val androidPermissions = mutableListOf<String>()

                for (resource in request.resources) {
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPermissions.add(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPermissions.add(Manifest.permission.RECORD_AUDIO)
                    }
                }

                if (androidPermissions.isNotEmpty()) {
                    requestPermissionsIfNeeded(androidPermissions.toTypedArray())
                } else {
                    request.grant(request.resources)
                }
            }

            // Запрос геолокации со стороны веб-страницы
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                pendingGeolocationOrigin = origin
                pendingGeolocationCallback = callback
                requestPermissionsIfNeeded(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }

            // Обработка выбора файлов
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                if (intent != null) {
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        return false
                    }
                } else {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    return false
                }
                return true
            }
        }
    }

    // Вспомогательный метод для проверки и запроса Android разрешений
    private fun requestPermissionsIfNeeded(permissions: Array<String>) {
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            // Если все разрешения уже были выданы пользователем ранее, даем доступ
            pendingPermissionRequest?.let { request ->
                request.grant(request.resources)
                pendingPermissionRequest = null
            }

            pendingGeolocationCallback?.let { callback ->
                callback.invoke(pendingGeolocationOrigin, true, false)
                pendingGeolocationCallback = null
                pendingGeolocationOrigin = null
            }
        }
    }

    // Сохраняем состояние WebView при повороте экрана
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // Восстанавливаем состояние WebView при повороте экрана
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        handler.post(colorUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(colorUpdateRunnable)
    }

    private fun updateColorsFromWebView() {
        if (webView.width == 0 || webView.height == 0) return
        
        val js = """
            (function() {
                function getColor(x, y) {
                    var el = document.elementFromPoint(x, y);
                    while (el) {
                        var color = window.getComputedStyle(el).backgroundColor;
                        if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') {
                            return color;
                        }
                        el = el.parentElement;
                    }
                    var bodyColor = window.getComputedStyle(document.body).backgroundColor;
                    return (bodyColor !== 'rgba(0, 0, 0, 0)' && bodyColor !== 'transparent') ? bodyColor : 'rgb(255, 255, 255)';
                }
                return getColor(10, 10) + '|' + getColor(10, window.innerHeight - 10);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                if (result == null || result == "null") return@evaluateJavascript
                val unquoted = result.replace("\"", "")
                val parts = unquoted.split("|")
                if (parts.size == 2) {
                    val topColor = parseCssColor(parts[0])
                    val bottomColor = parseCssColor(parts[1])
                    
                    window.decorView.setBackgroundColor(topColor)
                    window.statusBarColor = topColor
                    window.navigationBarColor = bottomColor
                    
                    val isTopLight = androidx.core.graphics.ColorUtils.calculateLuminance(topColor) > 0.5
                    val isBottomLight = androidx.core.graphics.ColorUtils.calculateLuminance(bottomColor) > 0.5
                    
                    val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                    controller.isAppearanceLightStatusBars = isTopLight
                    controller.isAppearanceLightNavigationBars = isBottomLight
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseCssColor(cssColor: String): Int {
        try {
            val colorStr = cssColor.lowercase().trim()
            if (colorStr.startsWith("rgb")) {
                val values = colorStr.substringAfter("(").substringBefore(")").split(",")
                if (values.size >= 3) {
                    val r = values[0].trim().toIntOrNull() ?: 255
                    val g = values[1].trim().toIntOrNull() ?: 255
                    val b = values[2].trim().toIntOrNull() ?: 255
                    return android.graphics.Color.rgb(r, g, b)
                }
            } else if (colorStr.startsWith("#")) {
                return android.graphics.Color.parseColor(colorStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return android.graphics.Color.WHITE
    }

    private fun handlePageTitleChange(title: String?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                return
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (title.isNullOrEmpty() || title.trim().equals("MAX", ignoreCase = true)) {
            notificationManager.cancel(1001)
        } else {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val builder = androidx.core.app.NotificationCompat.Builder(this, "webmax_notifications")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("WebMax")
                .setContentText(title)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            try {
                notificationManager.notify(1001, builder.build())
            } catch (e: SecurityException) {
                // Нет разрешения
            }
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "Уведомления WebMax"
            val descriptionText = "Уведомления о новых сообщениях"
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("webmax_notifications", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun checkBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("asked_battery", false)) {
                    prefs.edit().putBoolean("asked_battery", true).apply()
                    AlertDialog.Builder(this)
                        .setTitle("Работа уведомлений")
                        .setMessage("Внимание: если вы смахнете приложение из «Недавних» (полностью закроете его), уведомления могут приходить с задержкой до 15 минут из-за ограничений Android.\n\nДля стабильной работы уведомлений, пожалуйста, отключите режим энергосбережения для этого приложения.")
                        .setPositiveButton("Понятно, настроить") { _, _ ->
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            }
        }
    }
}
