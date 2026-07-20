package com.example.lanshare

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.lanshare.databinding.ActivityMainBinding

/**
 * Acilis ekrani: kullanici rol (Yayinci/Izleyici), roomId ve signaling
 * sunucusunun IP:PORT bilgisini girer; secime gore ilgili Activity baslatilir.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("lanshare", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Onceki degerleri geri yukle
        prefs.getString("roomId", null)?.let { binding.roomIdInput.setText(it) }
        prefs.getString("server", null)?.let { binding.serverInput.setText(it) }

        binding.publisherButton.setOnClickListener { launch("publisher") }
        binding.viewerButton.setOnClickListener { launch("viewer") }
    }

    private fun launch(role: String) {
        val roomId = binding.roomIdInput.text.toString().trim()
        val server = binding.serverInput.text.toString().trim()
        if (roomId.isEmpty() || server.isEmpty()) {
            Toast.makeText(this, "roomId ve sunucu adresi gerekli", Toast.LENGTH_SHORT).show()
            return
        }
        val parts = server.split(":")
        if (parts.size != 2 || parts[1].toIntOrNull() == null) {
            Toast.makeText(this, "Sunucu formati IP:PORT olmali", Toast.LENGTH_SHORT).show()
            return
        }
        val host = parts[0]
        val port = parts[1].toInt()
        val withAudio = binding.audioSwitch.isChecked

        prefs.edit()
            .putString("roomId", roomId)
            .putString("server", server)
            .apply()

        val target = if (role == "publisher") PublisherActivity::class.java else ViewerActivity::class.java
        val intent = Intent(this, target).apply {
            putExtra(EXTRA_ROOM, roomId)
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            // Ses yalnizca yayinci icin anlamli
            putExtra(EXTRA_AUDIO, role == "publisher" && withAudio)
        }
        startActivity(intent)
    }
}
