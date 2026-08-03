# LanShare — Teknik Dokümantasyon

LAN üzerinde (aynı Wi-Fi ağı) Android cihazlar arası düşük gecikmeli ekran paylaşımı uygulaması. Tek APK hem **Yayıncı** (ekranını paylaşan telefon) hem **İzleyici** (görüntüleyen telefon/Android TV) rolünü üstlenir. Yalnızca DRM'siz içerik paylaşılır; koruma aşma yoktur.

## Teknoloji Yığını (Özet)

| Katman | Teknoloji |
|---|---|
| Dil | Kotlin 2.0.21 (JVM target 17) |
| Derleme | Gradle (Kotlin DSL), Android Gradle Plugin 8.7.3, compileSdk 35 / targetSdk 34 / minSdk 26 |
| Medya taşıma | WebRTC — `io.github.webrtc-sdk:android:125.6422.07` (LiveKit fork, libwebrtc m125, paket `org.webrtc`) |
| Signaling (istemci) | OkHttp 4.12.0 WebSocket |
| Signaling (sunucu, cihaz içi) | Java-WebSocket 1.6.0 (`WebSocketServer`) |
| Cihaz keşfi | Android NSD (mDNS) — `_lanshare._tcp` servisi |
| Ekran yakalama | MediaProjection + `mediaProjection` tipli Foreground Service |
| İç ses yakalama | `AudioPlaybackCaptureConfiguration` (Android 10+) + WebRTC `JavaAudioDeviceModule.AudioBufferCallback` |
| UI | Klasik View sistemi + ViewBinding, Material 3 (material 1.12.0), AppCompat 1.7.0, ConstraintLayout |
| Yerelleştirme | 5 dil (EN taban `values/`, TR/AR/ES/DE), AppCompat per-app locales (`autoStoreLocales`) |
| Mesaj formatı | JSON (`org.json`) — SDP/ICE signaling protokolü |

Harici bağımlılık **yok denecek kadar az**: STUN/TURN sunucusu kullanılmaz (LAN'da host candidate yeterli), signaling sunucusu da uygulamanın içine gömülüdür. Mac'teki Node.js `ws` sunucusu (`~/signaling-server`) yalnızca manuel/fallback senaryo içindir.

## Mimari

```
┌─────────────── Yayıncı cihaz ───────────────┐        ┌────── İzleyici cihaz ──────┐
│ MainActivity (rol seçimi, Eşleştir/NSD)     │        │ MainActivity                │
│   └─ PublisherActivity                      │        │   └─ ViewerActivity         │
│        ├─ ScreenCaptureService (FGS)        │        │        ├─ SignalingClient ──┼──┐
│        ├─ ScreenCapturerAndroid (WebRTC)    │        │        ├─ WebRtcSession     │  │
│        ├─ InternalAudioCapture (opsiyonel)  │        │        └─ SurfaceViewRenderer│ │
│        ├─ WebRtcSession ═══ SRTP/RTP video+ses ═════════════════╝ (H264/HEVC hw)  │  │
│        ├─ SignalingClient (ws://127.0.0.1)  │        └────────────────────────────┘  │
│        └─ EmbeddedSignalingServer ◄──────── ws://<yayıncı-IP>:<ephemeral> ──────────┘
│             ▲ port NSD (_lanshare._tcp) ile duyurulur                                │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### Modüller (app/src/main/java/com/example/lanshare/)

- **`Common.kt`** — Ortak sabitler: log etiketi (`LANSHARE`), Intent extra anahtarları, video hedefleri (1920×1080, 60 fps, 8–12 Mbps), ses formatı (48 kHz mono PCM).
- **`MainActivity.kt`** — Rol seçimi (Yayınla / İzle), NSD "Eşleştir" akışı: keşfedilen cihaz kartları, dokun-bağlan; Gelişmiş bölümünde manuel IP:PORT fallback.
- **`PublisherActivity.kt`** — Yayın orkestrasyonu: MediaProjection izni → FGS başlat → `ScreenCapturerAndroid` ile yakalama → video/ses track'leri → offer/answer → codec tercihi → `setBitrate` kickstart → canlı istatistik satırı (encoder fps, gerçek bitrate, kalite kısıtlama sebebi).
- **`ViewerActivity.kt`** — `SurfaceViewRenderer` üzerinde uzak video; dokunma/OK tuşu ile açılıp kapanan istatistik overlay'i (çözünürlük, fps, bitrate, codec, A/V senkron farkı); KEEP_SCREEN_ON.
- **`WebRtcSession.kt`** — Publisher ve Viewer'ın paylaştığı WebRTC çekirdeği (ayrıntı aşağıda).
- **`SignalingClient.kt`** — OkHttp WebSocket istemcisi; JSON tel protokolü (aşağıda).
- **`EmbeddedSignalingServer.kt`** — Cihaz içi WebSocket signaling sunucusu; Node `server.js`'in birebir portu. Port 0 (ephemeral) ile bağlanır, gerçek port `onStart` sonrası `getPort()` ile okunur ve NSD'yle duyurulur. Boş odaya LAN'dan ilk join gelen peer'ın rolüne göre ev sahibinde karşı rol otomatik açılır (dokun-bağlan daveti).
- **`NsdHelper.kt`** — NSD kayıt + keşif; `MulticastLock` alır, kendi kaydını filtreler, resolve isteklerini kuyruklar (NSD resolve seri çalışmak zorunda), tüm callback'leri main thread'e taşır.
- **`PairingSession.kt`** — Eşleştirme akışının süreç içi singleton durumu (sunucu/NSD yaşam döngüsü Activity geçişlerinde korunur).
- **`ScreenCaptureService.kt`** — `mediaProjection` (+ ses açıksa `microphone`) tipli foreground service. Android 14 kuralı: MediaProjection token'ı ancak FGS **aktifken** kullanılabilir → `readyListener` tek atımlık callback'i ile Activity'ye "artık güvenli" sinyali verilir, yakalama ondan sonra başlar.
- **`InternalAudioCapture.kt`** — MediaProjection'dan `AudioPlaybackCaptureConfiguration` ile iç ses (medya/oyun usage'ları) yakalar; PCM'i ring buffer'da tutar. Gecikme bütçesi: ring'de en fazla ~60 ms birikir, fazlası atılır (A/V senkronun kalıcı kaymasını önler).
- **`SettingsStore.kt`** — SharedPreferences: fps (30/48/60), kalite (720p/900p/1080p), bitrate (auto/sabit), overlay ve canlı istatistik bayrakları, dil-ilk-açılış bayrağı.
- **`HelpActivity.kt`** — Adım adım kullanım kılavuzu (5 dil).

## WebRTC Ayrıntıları

- **Kütüphane seçimi**: `io.github.webrtc-sdk` (LiveKit fork). Daha önce kullanılan `io.getstream:stream-webrtc-android:1.3.8`'in `setAudioRecordDataCallback` API'si bytecode'da bağlanmamış ölü koddu (iç ses enjeksiyonu sessizce çalışmıyordu); fork'ta `AudioBufferCallback` gerçekten `AudioRecordThread` içinde çağrılıyor (javap ile doğrulandı).
- **PeerConnection yapılandırması**: Unified Plan; ICE sunucu listesi **boş** (STUN/TURN yok); `GATHER_ONCE` — `CONTINUALLY` politikası `NetworkMonitor`'a bağımlı olduğundan bazı cihazlarda hiç aday üretmiyordu (`ACCESS_NETWORK_STATE` izni de bu yüzden şart).
- **Codec**: `DefaultVideoEncoderFactory` donanım H264 (High Profile) + VP8; tercih sıralaması `RtpTransceiver.setCodecPreferences` ile (H264 birincil, cihaz destekliyorsa `MediaCodecList` kontrolüyle HEVC/H265 seçilebilir); ayrıca SDP munging fallback'i (`preferCodec`).
- **Bitrate**: `RtpSender.setParameters` üzerinden min/max (kaliteye göre 8–12 Mbps); yayın başında `setBitrate` "kickstart" ile BWE'nin düşükten başlaması engellenir.
- **fps notu**: `adaptOutputFormat` bilinçli olarak **kullanılmaz** — içindeki fps sınırlayıcı 60 fps'i ~30'a düşürüyordu; çözünürlük/fps doğrudan capture formatıyla belirlenir.
- **ICE aday kuyruğu**: remote description set edilmeden gelen adaylar `pendingIce` listesinde bekletilir, set sonrası uygulanır (yarış koşulu önlemi).
- **İstatistik**: `getStats` ile outbound (encoder fps, `qualityLimitationReason`, encoder implementasyonu, hedef/gerçek bitrate, encode süresi) ve inbound (çözünürlük, fps, codec) metrikleri + jitter-buffer tabanlı A/V senkron farkı okunur.

## Ses Hattı (mikrofon asla gitmez)

1. `JavaAudioDeviceModule` 48 kHz mono giriş ile kurulur; `setMicrophoneMute(true)` mic örneklerini sıfırlar.
2. `AudioBufferCallback.onBuffer`, mute sonrası ama native'e gitmeden önce çağrılır; `InternalAudioCapture.fill()` buffer'ı **tamamen** iç ses PCM'i ile değiştirir (mic'e karışma ihtimali yapısal olarak yok).
3. İç ses `AudioPlaybackCaptureConfiguration` ile aynı MediaProjection'dan alınır; yalnızca `allowAudioPlaybackCapture` izni veren uygulamaların sesi gelir, DRM'li içerik sessizdir (tasarım gereği).

## Signaling Protokolü (JSON / WebSocket)

İstemci → sunucu: `{"type":"join","roomId","role"}`, `offer`, `answer`, `ice`, `bye`.
Sunucu → istemci: `joined`, `peer-joined`, `peer-left`, `error`; `offer/answer/ice` aynı odadaki diğer peer'lara relay edilir. Eşleştirme akışında sabit oda adı `"local"` kullanılır; ev sahibi `ws://127.0.0.1:<port>`'a, karşı cihaz NSD'den çözülen `ws://<IP>:<port>`'a bağlanır. Aynı protokolü konuşan Node.js fallback sunucusu `~/signaling-server`'dadır (repo dışı).

## İzinler ve Platform Notları

- `INTERNET`, `ACCESS_NETWORK_STATE` (ICE aday üretimi için şart), `ACCESS_WIFI_STATE` + `CHANGE_WIFI_MULTICAST_STATE` (mDNS), `FOREGROUND_SERVICE(_MEDIA_PROJECTION|_MICROPHONE)`, `POST_NOTIFICATIONS`, `RECORD_AUDIO` (yalnız iç ses yakalama; AudioPlaybackCapture bunu gerektirir).
- `usesCleartextTraffic=true` — LAN içi `ws://` (TLS'siz WebSocket) için.
- Android TV: `LEANBACK_LAUNCHER` kategorisi, TV banner'ı; `touchscreen`/`leanback`/`microphone` feature'ları `required=false` (izleyici TV'ye mikrofon şartı olmadan kurulur).
- Türkçe locale tuzağı: Gradle çıktısı "BUİLD SUCCESSFUL" (noktalı İ) yazar — CI/grep'lerde dikkate alın.

## Derleme

Sistem Java 25 AGP ile uyumsuz; Android Studio'nun JBR 21'i kullanılır:

```bash
cd ~/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew assembleDebug
# Çıktı: app/build/outputs/apk/debug/app-debug.apk
```

Loglama: `adb logcat -s LANSHARE:V`. Test rehberi: `README-TEST.md`.

## Performans Karakteristiği (saha notları)

- 1080p60 hedeflenebilir ancak test telefonunda encoder ~50 ms/kare ile darboğaz (fiili ~30 fps'e kısıtlanıyor, `qualityLimitationReason=cpu`); 720p60 rahat çalışır — bu yüzden varsayılan kalite 720p'dir.
- A/V senkron: iç ses ring'inde 60 ms backlog tavanı ile ses-görüntü kayması giderildi; WebRTC'nin RTCP SR tabanlı lip-sync'i üzerine jitter-buffer farkı overlay'de izlenebilir.
