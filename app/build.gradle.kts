plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "ru.alexeyrf.webmax"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.alexeyrf.webmax" // Default, will be overridden by flavor
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "push"
    productFlavors {
        create("fcm") {
            dimension = "push"
            applicationId = "ru.oneme.app"
            buildConfigField("boolean", "IS_FCM", "true")
        }
        create("non_fcm") {
            dimension = "push"
            applicationId = "ru.alexeyrf.webmax"
            buildConfigField("boolean", "IS_FCM", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.androidx.work)
    
    // Зависимости Firebase (подключаются только в сборке fcm)
    "fcmImplementation"(platform("com.google.firebase:firebase-bom:33.7.0"))
    "fcmImplementation"("com.google.firebase:firebase-messaging-ktx")
}