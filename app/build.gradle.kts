plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.lanshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.lanshare"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // WebRTC (LiveKit fork, libwebrtc m125; paket adi org.webrtc, donanim H264 destekli)
    // NOT: getstream 1.3.8'den gecildi cunku onun setAudioRecordDataCallback API'si
    // bagli degildi (ic ses enjeksiyonu calismiyordu). Bu fork'ta AudioBufferCallback
    // gercekten AudioRecordThread icinde cagriliyor (bytecode dogrulandi).
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    // Signaling icin WebSocket istemcisi
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Gomulu signaling sunucusu (cihaz ici; Node sunucusuna bagimliligi kaldirir)
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
