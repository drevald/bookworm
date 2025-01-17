package com.valdr.bookworm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var captureButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        resultTextView = findViewById(R.id.resultTextView)
        captureButton = findViewById(R.id.captureButton)

        captureButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
            }
        }
    }

    private fun launchCamera() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            resultTextView.text = "Camera permission is required to take pictures."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as Bitmap
            imageView.setImageBitmap(imageBitmap)
            val isbns = processImage(imageBitmap)
        }
    }

    fun processImage(bitmap: Bitmap) : List<String> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val foundIsbns = mutableListOf<String>()

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                val isbnPattern = "\\bISBN(?:-13)?:?\\s?(\\d{3}-?\\d{1,5}-?\\d{1,7}-?\\d{1,7}-?\\d{1})"
                val pattern = Pattern.compile(isbnPattern)
                val matcher = pattern.matcher(text)

                while (matcher.find()) {
                    foundIsbns.add(matcher.group(1) ?: "")
                }

                resultTextView.text = if (foundIsbns.isNotEmpty()) {
                    "Found ISBNs: ${foundIsbns.joinToString(", ")}"
                } else {
                    "No ISBN found."
                }
            }
            .addOnFailureListener { e ->
                resultTextView.text = "Error: ${e.message}"
            }
        return foundIsbns;
    }

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1
        private const val CAMERA_REQUEST_CODE = 100
    }
}
