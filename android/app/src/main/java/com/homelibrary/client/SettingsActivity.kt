package com.homelibrary.client

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.homelibrary.client.databinding.ActivitySettingsBinding

/**
 * Settings screen for configuring app preferences.
 * 
 * Allows users to select the server address for gRPC communication.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        setupServerOptions()
        setupLanguageOptions()
    }

    private fun setupServerOptions() {
        val currentHost = AppSettings.getServerHost(this)
        val radioGroup = binding.serverRadioGroup

        AppSettings.SERVER_OPTIONS.forEachIndexed { index, (host, label) ->
            val radioButton = RadioButton(this).apply {
                id = index
                text = label
                textSize = 16f
                setPadding(48, 24, 48, 24)
                isChecked = (host == currentHost)
            }
            radioGroup.addView(radioButton)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedHost = AppSettings.SERVER_OPTIONS[checkedId].first
            AppSettings.setServerHost(this, selectedHost)
            // Reset the UploadManager so it picks up new settings
            UploadManager.resetInstance()
        }
    }

    private fun setupLanguageOptions() {
        val currentLang = AppSettings.getOcrLanguage(this)
        val radioGroup = binding.languageRadioGroup

        AppSettings.LANGUAGE_OPTIONS.forEachIndexed { index, (lang, label) ->
            val radioButton = RadioButton(this).apply {
                id = index + 100 // Avoid ID conflict with server options
                text = label
                textSize = 16f
                setPadding(48, 24, 48, 24)
                isChecked = (lang == currentLang)
            }
            radioGroup.addView(radioButton)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            // Adjust index for ID offset
            val index = checkedId - 100
            if (index >= 0 && index < AppSettings.LANGUAGE_OPTIONS.size) {
                val selectedLang = AppSettings.LANGUAGE_OPTIONS[index].first
                AppSettings.setOcrLanguage(this, selectedLang)
            }
        }
    }
}
