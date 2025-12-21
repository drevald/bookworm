package com.homelibrary.client

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.homelibrary.client.databinding.ActivityReviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding
    private var capturedUris: ArrayList<Uri>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        capturedUris = intent.getParcelableArrayListExtra("captured_uris")

        if (capturedUris == null || capturedUris!!.size < 2) {
            Toast.makeText(this, "Error: Missing images", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.statusText.text = "Captured ${capturedUris!!.size} images. Ready to upload."

        binding.uploadButton.setOnClickListener {
            uploadBook()
        }
    }

    private fun uploadBook() {
        binding.uploadButton.isEnabled = false
        binding.statusText.text = "Uploading..."

        val isEmulator = android.os.Build.FINGERPRINT.contains("generic") || 
            android.os.Build.FINGERPRINT.contains("unknown") ||
            android.os.Build.MODEL.contains("google_sdk") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.MODEL.contains("Android SDK built for x86") ||
            android.os.Build.MANUFACTURER.contains("Genymotion") ||
            (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic")) ||
            "google_sdk" == android.os.Build.PRODUCT

        val host = if (isEmulator) "10.0.2.2" else "192.168.0.106"
        val client = BookServiceClient(host, 9090)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                client.uploadBook(
                    applicationContext,
                    capturedUris!![0], // Cover
                    capturedUris!![1]  // Info
                ).collect { result ->
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is BookServiceClient.UploadResult.Success -> {
                                binding.statusText.text = "Success! Book ID: ${result.response.bookId}\nTitle: ${result.response.title}"
                                Toast.makeText(this@ReviewActivity, "Upload Successful", Toast.LENGTH_LONG).show()
                            }
                            is BookServiceClient.UploadResult.Error -> {
                                binding.statusText.text = "Error: ${result.message}"
                                binding.uploadButton.isEnabled = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "Exception: ${e.message}"
                    binding.uploadButton.isEnabled = true
                }
            }
        }
    }
}
