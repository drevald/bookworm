package com.homelibrary.client

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.homelibrary.client.data.AppDatabase
import com.homelibrary.client.data.ShelfEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

class ShelfManagementActivity : AppCompatActivity() {

    private lateinit var shelvesRecyclerView: RecyclerView
    private lateinit var emptyStateText: android.widget.TextView
    private lateinit var addShelfFab: FloatingActionButton
    private lateinit var shelfAdapter: ShelfAdapter
    private lateinit var database: AppDatabase

    private var pendingShelfName: String? = null
    private var pendingShelfPhoto: ByteArray? = null
    private var editingShelf: ShelfEntity? = null

    private val capturePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imagePath = result.data?.getStringExtra(CaptureActivity.EXTRA_IMAGE_PATH)
            if (imagePath != null) {
                // Compress image to low-res thumbnail
                pendingShelfPhoto = compressImageToThumbnail(File(imagePath))

                // Check if we're editing an existing shelf or creating new
                if (editingShelf != null) {
                    // Update existing shelf with new photo
                    updateShelf(editingShelf!!.copy(photo = pendingShelfPhoto))
                    editingShelf = null
                    pendingShelfName = null
                    pendingShelfPhoto = null
                } else if (pendingShelfName != null) {
                    // Create new shelf with name and photo
                    createShelf(pendingShelfName, pendingShelfPhoto)
                    pendingShelfName = null
                    pendingShelfPhoto = null
                } else {
                    // Ask for name (optional)
                    showNameInputDialog()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shelf_management)

        // Initialize views
        shelvesRecyclerView = findViewById(R.id.shelvesRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        addShelfFab = findViewById(R.id.addShelfFab)

        // Initialize database
        database = AppDatabase.getDatabase(this)

        // Setup RecyclerView
        shelfAdapter = ShelfAdapter(
            onShelfClick = { shelf -> editShelf(shelf) },
            onShelfLongClick = { shelf -> confirmDeleteShelf(shelf) }
        )
        shelvesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ShelfManagementActivity)
            adapter = shelfAdapter
        }

        // FAB click - add new shelf
        addShelfFab.setOnClickListener {
            showAddShelfDialog()
        }

        // Observe shelves
        lifecycleScope.launch {
            database.shelfDao().getAllShelves().collectLatest { shelves ->
                shelfAdapter.submitList(shelves)

                // Show/hide empty state
                if (shelves.isEmpty()) {
                    emptyStateText.visibility = View.VISIBLE
                    shelvesRecyclerView.visibility = View.GONE
                } else {
                    emptyStateText.visibility = View.GONE
                    shelvesRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showAddShelfDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add Shelf")
            .setMessage("Choose how to create shelf:")
            .setPositiveButton("Take Photo") { _, _ ->
                captureShelfPhoto()
            }
            .setNegativeButton("Enter Name") { _, _ ->
                showNameInputDialog()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showNameInputDialog() {
        val input = EditText(this).apply {
            hint = "Shelf name (optional if photo provided)"
            setText(pendingShelfName ?: "")
        }

        AlertDialog.Builder(this)
            .setTitle("Shelf Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val shelfName = input.text.toString().trim()
                val name = if (shelfName.isNotEmpty()) shelfName else null

                if (name == null && pendingShelfPhoto == null) {
                    Toast.makeText(this, "Please provide either a name or photo", Toast.LENGTH_SHORT).show()
                } else {
                    createShelf(name, pendingShelfPhoto)
                    pendingShelfName = null
                    pendingShelfPhoto = null
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingShelfName = null
                pendingShelfPhoto = null
            }
            .show()
    }

    private fun captureShelfPhoto() {
        val intent = Intent(this, CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_PAGE_TYPE, "SHELF")
        }
        capturePhotoLauncher.launch(intent)
    }

    private fun createShelf(name: String?, photo: ByteArray?) {
        lifecycleScope.launch {
            val shelfName = name ?: "Shelf ${System.currentTimeMillis()}"
            val shelf = ShelfEntity(
                name = shelfName,
                photo = photo,
                createdAt = System.currentTimeMillis()
            )
            database.shelfDao().insertShelf(shelf)
            Toast.makeText(this@ShelfManagementActivity, "Shelf added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun compressImageToThumbnail(imageFile: File, maxSize: Int = 200): ByteArray? {
        return try {
            // Decode image
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)

            // Calculate scaled dimensions
            val ratio = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaledWidth = (bitmap.width * ratio).toInt()
            val scaledHeight = (bitmap.height * ratio).toInt()

            // Create scaled bitmap
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

            // Compress to JPEG byte array
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val result = outputStream.toByteArray()

            // Cleanup
            bitmap.recycle()
            scaledBitmap.recycle()

            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun editShelf(shelf: ShelfEntity) {
        AlertDialog.Builder(this)
            .setTitle("Edit Shelf")
            .setMessage("What would you like to edit?")
            .setPositiveButton("Change Photo") { _, _ ->
                editingShelf = shelf
                captureShelfPhoto()
            }
            .setNegativeButton("Rename") { _, _ ->
                showRenameDialog(shelf)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(shelf: ShelfEntity) {
        val input = EditText(this).apply {
            setText(shelf.name)
            hint = "Shelf name"
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Shelf")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    updateShelf(shelf.copy(name = newName))
                } else {
                    Toast.makeText(this, "Shelf name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateShelf(shelf: ShelfEntity) {
        lifecycleScope.launch {
            database.shelfDao().updateShelf(shelf)
            Toast.makeText(this@ShelfManagementActivity, "Shelf updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteShelf(shelf: ShelfEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Shelf")
            .setMessage("Delete shelf '${shelf.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                deleteShelf(shelf)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteShelf(shelf: ShelfEntity) {
        lifecycleScope.launch {
            database.shelfDao().deleteShelfById(shelf.id)
            Toast.makeText(this@ShelfManagementActivity, "Shelf deleted", Toast.LENGTH_SHORT).show()
        }
    }
}
