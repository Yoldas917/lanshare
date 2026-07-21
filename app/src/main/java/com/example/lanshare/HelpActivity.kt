package com.example.lanshare

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.lanshare.databinding.ActivityHelpBinding
import com.google.android.material.card.MaterialCardView

/**
 * "Nasil kullanilir?" ekrani: tek kartta 7 numarali adim + ipuclari karti.
 * Tum metinler string kaynagindan gelir (5 dil); icerik programatik uretilir.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        fun newCard(): Pair<MaterialCardView, LinearLayout> {
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
            card.addView(inner)
            return card to inner
        }

        // 1) Adimlar karti: 7 numarali satir (rozet + metin)
        val steps = listOf(
            R.string.help_step1,
            R.string.help_step2,
            R.string.help_step3,
            R.string.help_step4,
            R.string.help_step5,
            R.string.help_step6,
            R.string.help_step7
        )
        val (stepsCard, stepsInner) = newCard()
        for ((index, stepRes) in steps.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = dp(10) }
            }
            row.addView(TextView(this).apply {
                text = (index + 1).toString()
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(getColor(R.color.blue_primary_dark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    dp(24), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            row.addView(TextView(this).apply {
                setText(stepRes)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            stepsInner.addView(row)
        }
        binding.helpContainer.addView(stepsCard)

        // 2) Ipuclari karti: baslik + 3 madde
        val tips = listOf(R.string.help_tip1, R.string.help_tip2, R.string.help_tip3)
        val (tipsCard, tipsInner) = newCard()
        tipsInner.addView(TextView(this).apply {
            setText(R.string.help_tips_title)
            setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            )
            setTextColor(getColor(R.color.blue_primary_dark))
        })
        for (tipRes in tips) {
            tipsInner.addView(TextView(this).apply {
                text = "•  " + getString(tipRes)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            })
        }
        binding.helpContainer.addView(tipsCard)
    }
}
