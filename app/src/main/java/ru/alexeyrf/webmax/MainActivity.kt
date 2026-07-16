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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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

    // Лаунчер для запроса рантайм-разрешений Android (для камеры, микрофона и геолокации)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 1. Обработка разрешений для камеры и микрофона в WebView
        pendingPermissionRequest?.let { request ->
            val grantedList = mutableListOf<String>()
            if (permissions[Manifest.permission.CAMERA] == true) {
                grantedList.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            }
            if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
                grantedList.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }

            if (grantedList.isNotEmpty()) {
                request.grant(grantedList.toTypedArray())
            } else {
                request.deny()
            }
            pendingPermissionRequest = null
        }

        // 2. Обработка разрешений для геолокации
        if (pendingGeolocationCallback != null) {
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, true, false)
            } else {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, false, false)
            }
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            webView.loadUrl("https://web.max.ru")
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

    private fun setupWebView() {
        val settings: WebSettings = webView.settings

        // Включаем JavaScript (нужно для большинства современных сайтов)
        settings.javaScriptEnabled = true

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
            // Если все разрешения уже были выданы пользователем ранее, даем доступ WebView
            pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
            pendingPermissionRequest = null

            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, true, false)
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null
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
        
        try {
            // Получаем цвета через программную отрисовку (работает всегда)
            val topColor = getWebViewPixelColor(10, 10)
            val bottomColor = getWebViewPixelColor(10, webView.height - 10)
            
            // Также меняем фон самой Activity, как вы предложили
            window.decorView.setBackgroundColor(topColor)
            
            // И применяем к статус бару и навигации
            window.statusBarColor = topColor
            window.navigationBarColor = bottomColor
            
            val isTopLight = androidx.core.graphics.ColorUtils.calculateLuminance(topColor) > 0.5
            val isBottomLight = androidx.core.graphics.ColorUtils.calculateLuminance(bottomColor) > 0.5
            
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = isTopLight
            controller.isAppearanceLightNavigationBars = isBottomLight
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getWebViewPixelColor(x: Int, y: Int): Int {
        return try {
            val safeX = x.coerceIn(0, webView.width - 1)
            val safeY = y.coerceIn(0, webView.height - 1)
            val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.translate(-safeX.toFloat(), -safeY.toFloat())
            webView.draw(canvas)
            val color = bitmap.getPixel(0, 0)
            if (android.graphics.Color.alpha(color) == 0) {
                android.graphics.Color.WHITE
            } else {
                color
            }
        } catch (e: Exception) {
            android.graphics.Color.WHITE
        }
    }
}
