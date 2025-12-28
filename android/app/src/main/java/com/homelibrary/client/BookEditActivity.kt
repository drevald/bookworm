package com.homelibrary.client

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homelibrary.client.data.AppDatabase
import com.homelibrary.client.data.BookRepository
import com.homelibrary.client.data.PageEntity
import com.homelibrary.client.data.PageType
import com.homelibrary.client.data.ShelfEntity
import com.homelibrary.client.databinding.ActivityBookEditBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class BookEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookEditBinding
    private lateinit var repository: BookRepository
    private lateinit var pageAdapter: PageAdapter
    private lateinit var uploadManager: UploadManager
    private lateinit var database: AppDatabase

    private var bookId: Long = -1
    private var isNewBook: Boolean = false
    private var pendingPageType: PageType? = null
    private var shelves: List<ShelfEntity> = emptyList()
    private var selectedShelfId: Long? = null

    private val captureResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imagePath = result.data?.getStringExtra(CaptureActivity.EXTRA_IMAGE_PATH)
            val pageType = pendingPageType

            if (imagePath != null && pageType != null) {
                lifecycleScope.launch {
                    repository.addPage(bookId, pageType, File(imagePath))
                }
            }
            pendingPageType = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra(MainActivity.EXTRA_BOOK_ID, -1)
        isNewBook = intent.getBooleanExtra(MainActivity.EXTRA_IS_NEW_BOOK, false)

        if (bookId == -1L) {
            Toast.makeText(this, "Invalid book ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Initialize repository and upload manager
        database = AppDatabase.getDatabase(this)
        val imageStorageDir = File(filesDir, "book_images").also { it.mkdirs() }
        repository = BookRepository(database.bookDao(), imageStorageDir)
        uploadManager = UploadManager.getInstance(this)

        // Setup shelf spinner
        setupShelfSpinner()

        // Setup RecyclerView
        pageAdapter = PageAdapter(
            onRetakeClick = { page -> retakePage(page) },
            onDeleteClick = { page -> confirmDeletePage(page) },
            onChangeTypeClick = { page -> showChangeTypeDialog(page) },
            onImageClick = { page -> showFullScreenImage(page) }
        )
        binding.pagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@BookEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = pageAdapter
        }

        // Setup action buttons
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.addCoverButton.setOnClickListener { capturePhoto(PageType.COVER) }
        binding.addInfoButton.setOnClickListener { capturePhoto(PageType.INFO_PAGE) }
        binding.addPageButton.setOnClickListener { capturePhoto(PageType.OTHER) }
        binding.sendButton.setOnClickListener { sendBook() }
        binding.deleteBookButton.setOnClickListener { confirmDeleteBook() }

        // Observe pages
        lifecycleScope.launch {
            repository.getPagesForBook(bookId).collectLatest { pages ->
                pageAdapter.submitList(pages)
            }
        }

        // If new book, automatically start capture for cover
        if (isNewBook) {
            capturePhoto(PageType.COVER)
        }
    }

    private fun capturePhoto(pageType: PageType) {
        pendingPageType = pageType
        val intent = Intent(this, CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_PAGE_TYPE, pageType.name)
        }
        captureResult.launch(intent)
    }

    private fun retakePage(page: PageEntity) {
        // For simplicity, delete and recapture
        lifecycleScope.launch {
            repository.deletePage(page.id, page.imagePath)
        }
        capturePhoto(page.type)
    }

    private fun showChangeTypeDialog(page: PageEntity) {
        val pageTypes = PageType.values()
        val pageTypeNames = pageTypes.map { type ->
            when (type) {
                PageType.COVER -> "Cover"
                PageType.INFO_PAGE -> "Info Page"
                PageType.OTHER -> "Other Page"
            }
        }.toTypedArray()

        // Find current type index
        val currentIndex = pageTypes.indexOf(page.type)

        AlertDialog.Builder(this)
            .setTitle("Change Page Type")
            .setSingleChoiceItems(pageTypeNames, currentIndex) { dialog, which ->
                val newType = pageTypes[which]
                if (newType != page.type) {
                    lifecycleScope.launch {
                        repository.updatePageType(page.id, newType)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePage(page: PageEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Page")
            .setMessage("Delete this ${page.type.name.lowercase().replace('_', ' ')}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deletePage(page.id, page.imagePath)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBook() {
        AlertDialog.Builder(this)
            .setTitle("Delete Book")
            .setMessage("Are you sure you want to delete this book and all its pages?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteBook(bookId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendBook() {
        lifecycleScope.launch {
            // Save shelf selection before sending
            if (selectedShelfId != null) {
                database.bookDao().updateBookShelf(bookId, selectedShelfId)
            }

            val bookWithPages = repository.getBookWithPages(bookId)
            if (bookWithPages != null) {
                // Validate required pages
                val hasCover = bookWithPages.pages.any { it.type == PageType.COVER }
                val hasInfo = bookWithPages.pages.any { it.type == PageType.INFO_PAGE }

                if (!hasCover || !hasInfo) {
                    val missing = mutableListOf<String>()
                    if (!hasCover) missing.add("cover")
                    if (!hasInfo) missing.add("info page")
                    Toast.makeText(
                        this@BookEditActivity,
                        "Missing: ${missing.joinToString(", ")}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                uploadManager.uploadBook(bookWithPages)
                Toast.makeText(this@BookEditActivity, "Upload started", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showFullScreenImage(page: PageEntity) {
        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)

            try {
                val bitmap = BitmapFactory.decodeFile(page.imagePath)
                if (bitmap != null) {
                    setImageBitmap(bitmap)
                } else {
                    Toast.makeText(this@BookEditActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                    return@apply
                }
            } catch (e: Exception) {
                Toast.makeText(this@BookEditActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                return@apply
            }
        }

        AlertDialog.Builder(this)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .create()
            .apply {
                window?.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                show()
            }
    }

    private fun setupShelfSpinner() {
        lifecycleScope.launch {
            // Observe shelves
            database.shelfDao().getAllShelves().collectLatest { shelfList ->
                shelves = shelfList

                // Create adapter with "No shelf" option + shelf names
                val shelfNames = mutableListOf("No shelf")
                shelfNames.addAll(shelfList.map { it.name })

                val adapter = ArrayAdapter(
                    this@BookEditActivity,
                    android.R.layout.simple_spinner_item,
                    shelfNames
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.shelfSpinner.adapter = adapter

                // Load current book's shelf
                val book = repository.getBookById(bookId)
                if (book?.shelfId != null) {
                    val shelfIndex = shelfList.indexOfFirst { it.id == book.shelfId }
                    if (shelfIndex >= 0) {
                        binding.shelfSpinner.setSelection(shelfIndex + 1) // +1 for "No shelf" option
                        selectedShelfId = book.shelfId
                    }
                }

                // Handle shelf selection
                binding.shelfSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        selectedShelfId = if (position == 0) {
                            null // "No shelf" selected
                        } else {
                            shelves[position - 1].id
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {
                        selectedShelfId = null
                    }
                }
            }
        }
    }
}
