# LanShare

**Aynı Wi-Fi ağındaki Android cihazlar arasında düşük gecikmeli ekran paylaşımı.**
Telefonunuzun ekranını Android TV'ye veya başka bir telefona — internet, hesap,
sunucu ve kablo olmadan — yansıtın.

Tek APK iki rolü de üstlenir: **Yayıncı** (ekranını paylaşan cihaz) ve
**İzleyici** (görüntüleyen telefon / Android TV). Yalnızca DRM'siz içerik
paylaşılır; herhangi bir koruma aşma yoktur.

## Özellikler

- 📺 **1080p / 60 fps'e kadar** donanım hızlandırmalı H.264 (cihaz destekliyorsa HEVC) video
- 🔊 **Opsiyonel iç ses** — mikrofon değil, cihazda çalan medya sesi (Android 10+)
- 📡 **Sıfır yapılandırma:** cihazlar birbirini mDNS/NSD ile otomatik bulur, "Eşleştir" deyip dokunmak yeterli — IP adresi yazmak yok (isteyene Gelişmiş'te manuel IP:PORT da var)
- 🖧 **Tamamen yerel:** STUN/TURN yok, harici sunucu yok — signaling sunucusu bile uygulamanın içine gömülü; veri LAN dışına çıkmaz
- 📊 **Canlı istatistikler:** çözünürlük, fps, bitrate, codec ve A/V senkron farkı overlay'i
- ⚙️ Ayarlanabilir fps (30/48/60), kalite (720p/900p/1080p) ve bitrate
- 🌍 5 dil: Türkçe, İngilizce, Arapça (RTL), İspanyolca, Almanca
- 🎨 Material 3 arayüz, Android TV kumandasıyla kullanılabilir izleyici ekranı

## Nasıl çalışır?

```
Yayıncı (telefon)                                 İzleyici (telefon / TV)
MediaProjection ile ekran yakalama                SurfaceViewRenderer
        │                                                 ▲
        ▼                                                 │
WebRTC (libwebrtc m125) ══ SRTP/RTP video+ses (LAN) ══════╝
        ▲
        │ SDP/ICE signaling — ws://<yayıncı>:<port>
        └── Gömülü WebSocket sunucusu (port mDNS ile duyurulur)
```

Yayıncı cihaz kendi içinde küçük bir WebSocket signaling sunucusu açar ve bunu
`_lanshare._tcp` mDNS servisi olarak ağa duyurur. İzleyici servisi keşfeder,
bağlanır, SDP/ICE takası yapılır ve video doğrudan cihazdan cihaza (peer-to-peer)
akar. Mimari, protokol ve WebRTC ayrıntıları için: **[TEKNIK.md](TEKNIK.md)**

## Kullanım

1. Her iki cihaza APK'yı kurun (aynı Wi-Fi ağında olmalılar).
2. Yayıncı cihazda **Yayınla**'ya dokunun, ekran kaydı iznini verin.
3. İzleyici cihazda **Eşleştir**'i açın, listede beliren cihaza dokunun.
4. İzleyicide overlay'i açmak için ekrana dokunun (TV'de kumandanın OK tuşu).

Adım adım kılavuz uygulamanın içinde de mevcut (Yardım ekranı, 5 dil).
Test senaryoları için: [README-TEST.md](README-TEST.md)

## Derleme

Gereksinimler: JDK 17–21, Android SDK (compileSdk 35), minSdk 26 (Android 8.0+).

```bash
./gradlew assembleDebug
# çıktı: app/build/outputs/apk/debug/app-debug.apk
```

Android TV'ye kurulum için: `adb connect <tv-ip>:5555 && adb install app-debug.apk`

## Teknoloji

| | |
|---|---|
| Dil | Kotlin 2.0.21 |
| Medya | WebRTC — `io.github.webrtc-sdk:android` (libwebrtc m125) |
| Keşif | Android NSD (mDNS), `_lanshare._tcp` |
| Signaling | Gömülü Java-WebSocket sunucu + OkHttp WebSocket istemci, JSON |
| Yakalama | MediaProjection + Foreground Service, `AudioPlaybackCaptureConfiguration` |
| UI | Material 3, ViewBinding, AppCompat per-app locales |

Bu proje, yapay zekâ destekli geliştirme araçları (Claude) ile birlikte
geliştirilmiştir; mimari kararlar ve cihaz testleri geliştiriciye aittir.
