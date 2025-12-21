package com.homelibrary.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.homelibrary.client.databinding.ActivityBookEditBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class BookEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookEditBinding
    private lateinit var repository: BookRepository
    private lateinit var pageAdapter: PageAdapter
    private lateinit var uploadManager: UploadManager
    
    private var bookId: Long = -1
    private var isNewBook: Boolean = false
    private var pendingPageType: PageType? = null

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
        val database = AppDatabase.getDatabase(this)
        val imageStorageDir = File(filesDir, "book_images").also { it.mkdirs() }
        repository = BookRepository(database.bookDao(), imageStorageDir)
        uploadManager = UploadManager.getInstance(this)

        // Setup RecyclerView
        pageAdapter = PageAdapter(
            onRetakeClick = { page -> retakePage(page) },
            onDeleteClick = { page -> confirmDeletePage(page) }
        )
        binding.pagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@BookEditActivity)
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
}
