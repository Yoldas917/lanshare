package com.example.lanshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.lanshare.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Acilis ekrani: rol kartlari (Yayinla/Izle), oda+sunucu girisleri, ses anahtari,
 * kalite (720p/900p/1080p) ve fps (30/48/60) secicileri, gelismis bitrate,
 * dil secimi ve "Nasil kullanilir?". Tum secimler kalicidir (SharedPreferences /
 * AppCompat per-app locales).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        // Ilk acilista varsayilan dil INGILIZCE (sistem dili ne olursa olsun).
        // Kullanicinin sonraki secimi (autoStoreLocales) her zaman onceliklidir.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }

        restoreState()

        // Manuel yedek yol (Gelismis altinda): elle IP:PORT + rol
        binding.manualPublishButton.setOnClickListener { launch("publisher") }
        binding.manualViewButton.setOnClickListener { launch("viewer") }

        // Eslestir / Gorunur ol: gomulu signaling + NSD kayit + kesif
        binding.pairButton.setOnClickListener {
            if (!PairingSession.isActive) {
                binding.pairButton.isEnabled = false
                Thread {
                    val ok = PairingSession.start(applicationContext)
                    runOnUiThread {
                        binding.pairButton.isEnabled = true
                        if (!ok) {
                            // st_error format arg'lidir (%1$s) — argumani doldur
                            Toast.makeText(
                                this,
                                getString(R.string.st_error, "signaling"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        updatePairingUi()
                        renderNearby()
                    }
                }.start()
            } else {
                PairingSession.stop()
                updatePairingUi()
                renderNearby()
            }
        }
        binding.helpCard.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        binding.advancedHeader.setOnClickListener {
            binding.advancedContent.visibility =
                if (binding.advancedContent.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.liveStatsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.showLiveStats = checked
        }

        binding.qualityGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.quality = when (checkedId) {
                binding.btnQ1080.id -> SettingsStore.QUALITY_1080
                binding.btnQ900.id -> SettingsStore.QUALITY_900
                else -> SettingsStore.QUALITY_720
            }
        }
        binding.fpsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.fps = when (checkedId) {
                binding.btnFps30.id -> 30
                binding.btnFps48.id -> 48
                else -> 60
            }
        }
        binding.bitrateChips.setOnCheckedStateChangeListener { _, ids ->
            settings.bitrateBps = when (ids.firstOrNull()) {
                binding.chipBr6.id -> 6_000_000
                binding.chipBr8.id -> 8_000_000
                binding.chipBr12.id -> 12_000_000
                binding.chipBr20.id -> 20_000_000
                else -> 0 // auto
            }
        }
        binding.langChips.setOnCheckedStateChangeListener { _, ids ->
            val tag = when (ids.firstOrNull()) {
                binding.chipLangTr.id -> "tr"
                binding.chipLangAr.id -> "ar"
                binding.chipLangEs.id -> "es"
                binding.chipLangDe.id -> "de"
                else -> "en"
            }
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (current != tag) {
                // autoStoreLocales sayesinde kalici; activity otomatik yeniden olusur
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Kesif listesi degisince UI tazele; uzak join gelirse karsi rolu baslat
        PairingSession.onDevicesChanged = { renderNearby() }
        PairingSession.onInvite = { remoteRole -> runOnUiThread { handleInvite(remoteRole) } }
        updatePairingUi()
        renderNearby()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Uygulamadan tamamen cikista gorunurlugu kapat (recreate'te degil)
        if (isFinishing && PairingSession.isActive) PairingSession.stop()
    }

    private fun updatePairingUi() {
        val active = PairingSession.isActive
        binding.pairButton.setText(if (active) R.string.pair_toggle_off else R.string.pair_toggle_on)
        binding.pairHint.visibility = if (active) View.VISIBLE else View.GONE
        binding.nearbyLabel.visibility = if (active) View.VISIBLE else View.GONE
        // Gelismis altinda bilgi: bu cihazin adresi (manuel fallback icin)
        val ip = if (active) localIpAddress() else null
        if (ip != null) {
            binding.ownAddrText.text = getString(R.string.own_addr, "$ip:${PairingSession.port}")
            binding.ownAddrText.visibility = View.VISIBLE
        } else {
            binding.ownAddrText.visibility = View.GONE
        }
    }

    private fun renderNearby() {
        binding.nearbyContainer.removeAllViews()
        val active = PairingSession.isActive
        binding.nearbyEmpty.visibility =
            if (active && PairingSession.devices.isEmpty()) View.VISIBLE else View.GONE
        if (!active) return

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        for (dev in PairingSession.devices.values) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            inner.addView(TextView(this).apply {
                text = dev.name
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                )
                setTextColor(getColor(R.color.text_primary))
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            row.addView(MaterialButton(this).apply {
                setText(R.string.dev_share_to)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { connectTo(dev, "publisher") }
            })
            row.addView(MaterialButton(this).apply {
                setText(R.string.dev_watch)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
                setOnClickListener { connectTo(dev, "viewer") }
            })
            inner.addView(row)
            card.addView(inner)
            binding.nearbyContainer.addView(card)
        }
    }

    /** Karttan baglanti: hedef cihazin gomulu signaling'ine, sabit "local" odasi */
    private fun connectTo(dev: NearbyDevice, role: String) {
        launchTo(dev.host, dev.port, PAIR_ROOM, role)
    }

    /** Uzak cihaz bizim sunucumuza join oldu -> karsi rolu ustlen (127.0.0.1) */
    private fun handleInvite(remoteRole: String) {
        val myRole = if (remoteRole == "publisher") "viewer" else "publisher"
        launchTo("127.0.0.1", PairingSession.port, PAIR_ROOM, myRole)
    }

    private fun launchTo(host: String, port: Int, room: String, role: String) {
        val target = if (role == "publisher") PublisherActivity::class.java else ViewerActivity::class.java
        startActivity(Intent(this, target).apply {
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_AUDIO, role == "publisher" && binding.audioSwitch.isChecked)
        })
    }

    private fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun restoreState() {
        settings.roomId?.let { binding.roomIdInput.setText(it) }
        settings.server?.let { binding.serverInput.setText(it) }
        binding.liveStatsSwitch.isChecked = settings.showLiveStats

        binding.qualityGroup.check(
            when (settings.quality) {
                SettingsStore.QUALITY_1080 -> binding.btnQ1080.id
                SettingsStore.QUALITY_900 -> binding.btnQ900.id
                else -> binding.btnQ720.id
            }
        )
        binding.fpsGroup.check(
            when (settings.fps) {
                30 -> binding.btnFps30.id
                48 -> binding.btnFps48.id
                else -> binding.btnFps60.id
            }
        )
        binding.bitrateChips.check(
            when (settings.bitrateBps) {
                6_000_000 -> binding.chipBr6.id
                8_000_000 -> binding.chipBr8.id
                12_000_000 -> binding.chipBr12.id
                20_000_000 -> binding.chipBr20.id
                else -> binding.chipBrAuto.id
            }
        )
        if (settings.bitrateBps != 0) binding.advancedContent.visibility = View.VISIBLE

        val lang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        binding.langChips.check(
            when {
                lang.startsWith("tr") -> binding.chipLangTr.id
                lang.startsWith("en") -> binding.chipLangEn.id
                lang.startsWith("ar") -> binding.chipLangAr.id
                lang.startsWith("es") -> binding.chipLangEs.id
                lang.startsWith("de") -> binding.chipLangDe.id
                else -> binding.chipLangEn.id
            }
        )
    }

    private fun launch(role: String) {
        val roomId = binding.roomIdInput.text.toString().trim()
        val server = binding.serverInput.text.toString().trim()
        if (roomId.isEmpty() || server.isEmpty()) {
            Toast.makeText(this, R.string.err_fields_required, Toast.LENGTH_SHORT).show()
            return
        }
        val parts = server.split(":")
        if (parts.size != 2 || parts[1].toIntOrNull() == null) {
            Toast.makeText(this, R.string.err_server_format, Toast.LENGTH_SHORT).show()
            return
        }

        settings.roomId = roomId
        settings.server = server

        val target = if (role == "publisher") PublisherActivity::class.java else ViewerActivity::class.java
        startActivity(Intent(this, target).apply {
            putExtra(EXTRA_ROOM, roomId)
            putExtra(EXTRA_HOST, parts[0])
            putExtra(EXTRA_PORT, parts[1].toInt())
            putExtra(EXTRA_AUDIO, role == "publisher" && binding.audioSwitch.isChecked)
        })
    }
}
