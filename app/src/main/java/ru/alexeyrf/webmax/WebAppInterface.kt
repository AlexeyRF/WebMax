package ru.alexeyrf.webmax

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

// РАСКОММЕНТИРОВАТЬ ДЛЯ ИСПОЛЬЗОВАНИЯ FIREBASE И JS-МОСТА
class WebAppInterface(private val context: Context) {
    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // Веб-приложение вызывает этот метод, чтобы получить уникальный токен для отправки push-уведомлений
    @JavascriptInterface
    fun getDevicePushToken(): String {
        // Получаем сохраненный FCM токен (сохраняется в MyFirebaseMessagingService)
        val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        return prefs.getString("fcm_token", "") ?: ""
    }
}
