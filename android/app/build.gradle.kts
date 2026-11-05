import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alay.asistente"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alay.asistente"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Llave fija para que cada APK nuevo se instale encima del anterior sin desinstalar.
    signingConfigs {
        create("alay") {
            storeFile = file("alay.jks")
            storePassword = "alay1234"
            keyAlias = "alay"
            keyPassword = "alay1234"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("alay") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("alay")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Reconocimiento de voz sin conexión para la palabra de activación.
    implementation("com.alphacephei:vosk-android:0.3.75")
}
