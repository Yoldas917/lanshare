package com.example.lanshare

// Tum uygulamada ortak log etiketi -> `adb logcat -s LANSHARE:V`
const val TAG = "LANSHARE"

// Activity'ler arasi extra anahtarlari
const val EXTRA_ROOM = "extra_room"
const val EXTRA_HOST = "extra_host"
const val EXTRA_PORT = "extra_port"
const val EXTRA_AUDIO = "extra_audio"

// Servise FGS tipini bildirmek icin extra (ses -> microphone tipi de eklenir)
const val EXTRA_WITH_MIC = "extra_with_mic"

// Eslestirme (NSD) akisinda kullanilan sabit oda adi
const val PAIR_ROOM = "local"

// Video hedefleri (Faz 7: sabit 1080p60, ~12 Mbps)
const val VIDEO_WIDTH = 1920
const val VIDEO_HEIGHT = 1080
const val VIDEO_FPS = 60
const val VIDEO_MAX_BITRATE_BPS = 12_000_000   // 12 Mbps (ayarlanabilir hedef)
const val VIDEO_MIN_BITRATE_BPS = 8_000_000    // LAN'da yuksek taban tut (BWE takilmasin)

// Ses (ic ses yakalama) — WebRTC ADM ile ayni ornekleme hizi kullanilmali
const val AUDIO_SAMPLE_RATE = 48_000
const val AUDIO_CHANNELS = 1
