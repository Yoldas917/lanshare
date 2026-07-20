# LAN Ekran Paylaşımı — Android Test Rehberi (Faz 3 + Faz 7)

Bu uygulama tek APK'dır: açılışta **YAYINCI** veya **İZLEYİCİ** seçilir, `roomId` ve
signaling `IP:PORT` girilir. Hedef: **1080p @ 60fps**, donanım **H.264** (gerekirse HEVC
fallback), ~12 Mbps, WebRTC.

## Faz 7 yenilikleri
- **Opsiyonel ses:** Ana ekranda **"Sesi de paylaş"** anahtarı (varsayılan kapalı). Açıkken
  yayıncı cihazın **iç sesini** (AudioPlaybackCapture) WebRTC audio track olarak gönderir;
  izleyici TV hoparlöründen çalar. Korumalı (DRM) uygulamalar sesi paylaşmayabilir — beklenen.
  Ses açıkken telefonda **mikrofon (RECORD_AUDIO)** izni istenir.
- **1080p60:** capture 1920x1080@60, `degradationPreference=MAINTAIN_FRAMERATE` (fps sabit),
  bitrate 12 Mbps (min 8). Cihaz H264 1080p60 desteklemezse otomatik **HEVC** fallback (logda).
- **İzleyici overlay:** sağ üstte canlı **çözünürlük · fps · Mbps · codec** (`getStats`).

## Ön koşullar
- Signaling sunucusu çalışıyor olmalı:
  ```bash
  cd ~/signaling-server && npm start
  # -> ws://192.168.1.165:8080 (Mac'in LAN IP'si)
  ```
- Tüm cihazlar (Mac + telefon + TV) **aynı Wi-Fi/modeme** bağlı olmalı.
- Terminalde SDK ortamı (her yeni terminalde):
  ```bash
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
  ```

## APK yolu
```
~/android/app/build/outputs/apk/debug/app-debug.apk
```
Yeniden derlemek için:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd ~/android && ./gradlew assembleDebug
```

## 1) Telefon = YAYINCI (USB)
1. Telefonda: Ayarlar → Telefon Hakkında → **Yapı numarası**na 7 kez bas (Geliştirici modu),
   sonra Geliştirici seçenekleri → **USB hata ayıklama**yı aç. USB ile Mac'e bağla, "İzin ver" de.
2. Bağlantıyı doğrula:
   ```bash
   adb devices
   ```
3. Kur:
   ```bash
   adb -s <TELEFON_SERI> install -r ~/android/app/build/outputs/apk/debug/app-debug.apk
   ```
4. Uygulamayı aç → **YAYINCI** → `roomId=demo`, sunucu `192.168.1.165:8080` →
   "Şimdi başlat" ile ekran yakalama iznini ve bildirim iznini ver.

## 2) Android TV = İZLEYİCİ (ağ üzerinden adb)
1. TV'de: Ayarlar → Cihaz Tercihleri → **Hakkında** → **Yapı numarası**na 7 kez bas →
   Geliştirici seçenekleri → **USB/Ağ hata ayıklama**yı aç.
   TV'nin IP'sini not al: Ayarlar → Ağ ve İnternet (örn. `192.168.1.50`).
2. Mac'ten TV'ye bağlan (TV ekranında çıkan izni onayla):
   ```bash
   adb connect <TV_IP>:5555
   adb devices          # <TV_IP>:5555  device  görünmeli
   ```
3. APK'yı TV'ye kur ve başlat:
   ```bash
   adb -s <TV_IP>:5555 install -r ~/android/app/build/outputs/apk/debug/app-debug.apk
   adb -s <TV_IP>:5555 shell am start -n com.example.lanshare/.MainActivity
   ```
4. TV'de uzaktan kumandayla **İZLEYİCİ** seç, aynı `roomId=demo`, aynı sunucu IP'sini gir.
   (Klavye için kumanda yeterli; alanlar D-pad ile odaklanır.)

## 3) Doğrulama (loglar)
Her iki cihazda ayrı terminalde:
```bash
adb -s <CIHAZ> logcat -c        # eski loglari temizle
adb -s <CIHAZ> logcat -s LANSHARE:V
```
Beklenen akış:
- Yayıncı: `join → peer-joined → offer gonderildi → ICE: CHECKING → ICE: CONNECTED`
- İzleyici: `join → offer alindi → answer gonderildi → ICE: CONNECTED → Remote video baglandi`
- Signaling sunucusu konsolu: iki `join`, `relay 'offer'/'answer'/'ice'`.

**Başarı:** TV ekranında telefonun ekranı görünür (720p30). En azından her iki tarafta
`ICE: CONNECTED` loglanmalı.

## Sık sorunlar
- **`adb connect` reddediliyor:** TV'de ağ hata ayıklama izni penceresini onayla; aynı ağda olun.
- **Cleartext/bağlanamıyor:** IP:PORT doğru mu? Mac'te `npm start` çalışıyor mu? macOS güvenlik
  duvarı Node'a izin veriyor mu (Sistem Ayarları → Ağ → Güvenlik Duvarı)?
- **Yayıncıda siyah/görüntü yok ama ICE CONNECTED:** codec uyumu; loglarda encoder H264 mü bak.
- **TV'de uygulama görünmüyor:** `adb shell am start -n com.example.lanshare/.MainActivity` ile aç.
