package ru.alexeyrf.webmax

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BackgroundWebViewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        try {
            suspendCoroutine { continuation ->
                val webView = WebView(applicationContext)
                val settings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                var finished = false
                val timeoutHandler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!finished) {
                        finished = true
                        webView.destroy()
                        continuation.resume(Result.success())
                    }
                }
                
                // Тайм-аут 30 секунд для загрузки и отработки скриптов
                timeoutHandler.postDelayed(timeoutRunnable, 30000)

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        if (finished) return

                        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        if (title.isNullOrEmpty() || title.trim().equals("MAX", ignoreCase = true) || title.trim().equals("WebMax", ignoreCase = true)) {
                            notificationManager.cancel(1001)
                        } else {
                            val intent = android.content.Intent(applicationContext, MainActivity::class.java).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            val pendingIntent = android.app.PendingIntent.getActivity(
                                applicationContext, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            val builder = androidx.core.app.NotificationCompat.Builder(applicationContext, "webmax_notifications")
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
                        
                        // Если title изменился на что-то отличное от "MAX", завершаем работу раньше времени
                        if (!title.isNullOrEmpty() && !title.trim().equals("MAX", ignoreCase = true) && !title.trim().equals("WebMax", ignoreCase = true)) {
                            finished = true
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            webView.destroy()
                            continuation.resume(Result.success())
                        }
                    }
                }

                webView.loadUrl("https://web.max.ru")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
