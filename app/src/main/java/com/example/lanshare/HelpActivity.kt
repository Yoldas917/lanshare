package com.example.lanshare

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.lanshare.databinding.ActivityHelpBinding
import com.google.android.material.card.MaterialCardView

/**
 * "Nasil kullanilir?" ekrani: 5 adimlik kartli rehber. Tum metinler string
 * kaynagindan gelir (5 dil); kartlar tek sablondan programatik uretilir.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val steps = listOf(
            R.string.help_s1_title to R.string.help_s1_body,
            R.string.help_s2_title to R.string.help_s2_body,
            R.string.help_s3_title to R.string.help_s3_body,
            R.string.help_s4_title to R.string.help_s4_body,
            R.string.help_s5_title to R.string.help_s5_body
        )

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        for ((titleRes, bodyRes) in steps) {
            val card = MaterialCardView(this).apply {
                isFocusable = true // TV'de D-pad ile kaydirma odagi
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            inner.addView(TextView(this).apply {
                setText(titleRes)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                )
                setTextColor(getColor(R.color.blue_primary_dark))
            })
            inner.addView(TextView(this).apply {
                setText(bodyRes)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
            card.addView(inner)
            binding.helpContainer.addView(card)
        }
    }
}
