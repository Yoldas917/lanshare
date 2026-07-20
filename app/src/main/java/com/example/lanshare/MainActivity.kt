package com.example.lanshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.lanshare.databinding.ActivityMainBinding

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
        if (!settings.langInitialized) {
            settings.langInitialized = true
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
        }

        restoreState()

        // Manuel yedek yol (Gelismis altinda): elle IP:PORT + rol
        binding.manualPublishButton.setOnClickListener { launch("publisher") }
        binding.manualViewButton.setOnClickListener { launch("viewer") }
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
                binding.chipLangEn.id -> "en"
                binding.chipLangAr.id -> "ar"
                binding.chipLangEs.id -> "es"
                binding.chipLangDe.id -> "de"
                else -> null // sistem dili
            }
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val target = tag ?: ""
            if (current != target) {
                // autoStoreLocales sayesinde kalici; activity otomatik yeniden olusur
                AppCompatDelegate.setApplicationLocales(
                    if (tag == null) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
            }
        }
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
                else -> binding.chipLangSys.id
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
