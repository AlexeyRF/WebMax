package ru.alexeyrf.webmax

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class AuthActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvTitle: TextView
    private lateinit var etPin: EditText
    private lateinit var btnSubmit: Button

    private var wrongAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        prefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

        tvTitle = findViewById(R.id.tvTitle)
        etPin = findViewById(R.id.etPin)
        btnSubmit = findViewById(R.id.btnSubmit)

        if (!prefs.getBoolean("auth_configured", false)) {
            startConfigurationFlow()
        } else {
            startAuthenticationFlow()
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun startConfigurationFlow() {
        val methods = arrayOf("Без пароля", "ПИН-код", "ПИН-код + Биометрия")
        AlertDialog.Builder(this)
            .setTitle("Выберите метод входа")
            .setCancelable(false)
            .setItems(methods) { _, which ->
                when (which) {
                    0 -> saveConfigAndLaunch(0, "", 0, "")
                    1, 2 -> askForNewPin(which)
                }
            }
            .show()
    }

    private fun askForNewPin(methodId: Int) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Придумайте ПИН-код")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Далее") { _, _ ->
                val pin = input.text.toString()
                if (pin.isNotEmpty()) {
                    askForFailAction(methodId, pin)
                } else {
                    Toast.makeText(this, "ПИН-код не может быть пустым", Toast.LENGTH_SHORT).show()
                    askForNewPin(methodId)
                }
            }
            .show()
    }

    private fun askForFailAction(methodId: Int, pin: String) {
        val actions = arrayOf("Ничего", "Вывод подсказки", "Сброс приложения")
        AlertDialog.Builder(this)
            .setTitle("Действие при 5 неверных попытках")
            .setCancelable(false)
            .setItems(actions) { _, which ->
                if (which == 1) {
                    askForHint(methodId, pin)
                } else {
                    saveConfigAndLaunch(methodId, pin, which, "")
                }
            }
            .show()
    }

    private fun askForHint(methodId: Int, pin: String) {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Введите текст подсказки")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("ОК") { _, _ ->
                saveConfigAndLaunch(methodId, pin, 1, input.text.toString())
            }
            .show()
    }

    private fun saveConfigAndLaunch(methodId: Int, pin: String, failActionId: Int, hint: String) {
        prefs.edit()
            .putBoolean("auth_configured", true)
            .putInt("auth_method", methodId)
            .putString("auth_pin", pin)
            .putInt("auth_fail_action", failActionId)
            .putString("auth_hint", hint)
            .apply()
        launchMain()
    }

    private fun startAuthenticationFlow() {
        val methodId = prefs.getInt("auth_method", 0)
        if (methodId == 0) {
            launchMain()
            return
        }

        val savedPin = prefs.getString("auth_pin", "") ?: ""

        btnSubmit.setOnClickListener {
            val entered = etPin.text.toString()
            if (entered == savedPin) {
                launchMain()
            } else {
                wrongAttempts++
                etPin.text.clear()
                tvTitle.text = "Неверный ПИН-код ($wrongAttempts/5)"
                if (wrongAttempts >= 5) {
                    handleFiveFails()
                }
            }
        }

        if (methodId == 2) {
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    launchMain()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Bio error, user can still use PIN. We don't increment wrong attempts for bio failures.
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Bio failed (wrong finger), do not increment wrong attempts for PIN
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход по биометрии")
            .setSubtitle("Используйте отпечаток пальца или лицо для входа")
            .setNegativeButtonText("Использовать ПИН-код")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun handleFiveFails() {
        when (prefs.getInt("auth_fail_action", 0)) {
            0 -> {
                // Ничего
                Toast.makeText(this, "Слишком много неудачных попыток", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                // Вывод подсказки
                val hint = prefs.getString("auth_hint", "Нет подсказки")
                AlertDialog.Builder(this)
                    .setTitle("Подсказка")
                    .setMessage(hint)
                    .setPositiveButton("ОК", null)
                    .show()
            }
            2 -> {
                // Сброс приложения
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.clearApplicationUserData()
            }
        }
        wrongAttempts = 0
        tvTitle.text = "Введите ПИН-код"
    }
}
