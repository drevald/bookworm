package com.homelibrary.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homelibrary.client.data.AppDatabase
import com.homelibrary.client.data.BookRepository
import com.homelibrary.client.data.BookWithPages
import com.homelibrary.client.data.ShelfEntity
import com.homelibrary.client.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Main activity displaying the list of books.
 * 
 * Shows all books with their status, allows creating new books,
 * editing existing ones, and accessing settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: BookRepository
    private lateinit var bookAdapter: BookAdapter
    private lateinit var database: AppDatabase

    private var currentShelfId: Long? = null
    private var currentShelfName: String = "All Books"
    private val shelfPrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        database = AppDatabase.getDatabase(this)
        val imageStorageDir = File(filesDir, "book_images").also { it.mkdirs() }
        repository = BookRepository(database.bookDao(), imageStorageDir)

        // Load saved shelf preference
        loadShelfPreference()

        // Setup RecyclerView
        bookAdapter = BookAdapter(
            onBookClick = { bookWithPages -> openBookEdit(bookWithPages) }
        )
        binding.booksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = bookAdapter
        }

        // FAB click - create new book
        binding.addBookFab.setOnClickListener {
            if (allPermissionsGranted()) {
                createNewBook()
            } else {
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                )
            }
        }

        // Observe books filtered by current shelf
        observeBooks()
    }

    private fun loadShelfPreference() {
        val savedShelfId = shelfPrefs.getLong(KEY_SELECTED_SHELF, Long.MIN_VALUE)
        currentShelfId = when (savedShelfId) {
            Long.MIN_VALUE -> null  // Not set, show all books
            NO_SHELF_ID -> -1L  // "No Shelf" filter
            else -> savedShelfId  // Specific shelf
        }

        // Load shelf name if specific shelf is selected
        if (currentShelfId != null && currentShelfId != -1L) {
            lifecycleScope.launch {
                val shelf = database.shelfDao().getShelfById(currentShelfId!!)
                currentShelfName = shelf?.name ?: "All Books"
                updateTitle()
            }
        } else if (currentShelfId == -1L) {
            currentShelfName = "No Shelf"
            updateTitle()
        } else {
            currentShelfName = "All Books"
            updateTitle()
        }
    }

    private fun observeBooks() {
        lifecycleScope.launch {
            repository.getBooksWithPages(currentShelfId).collectLatest { books ->
                bookAdapter.submitList(books)

                // Show/hide empty state
                if (books.isEmpty()) {
                    binding.emptyStateText.visibility = View.VISIBLE
                    binding.booksRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyStateText.visibility = View.GONE
                    binding.booksRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateTitle() {
        supportActionBar?.title = currentShelfName
    }

    private fun createNewBook() {
        lifecycleScope.launch {
            val clientRefId = "book_${System.currentTimeMillis()}"
            // Assign to current shelf if it's a real shelf (not "All Books" or "No Shelf")
            val shelfIdForNewBook = if (currentShelfId != null && currentShelfId != -1L) {
                currentShelfId
            } else {
                null
            }
            val bookId = repository.createBook(clientRefId, shelfIdForNewBook)

            // Open book edit to add pages
            val intent = Intent(this@MainActivity, BookEditActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_IS_NEW_BOOK, true)
            }
            startActivity(intent)
        }
    }

    private fun openBookEdit(bookWithPages: BookWithPages) {
        val intent = Intent(this, BookEditActivity::class.java).apply {
            putExtra(EXTRA_BOOK_ID, bookWithPages.book.id)
            putExtra(EXTRA_IS_NEW_BOOK, false)
        }
        startActivity(intent)
    }



    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                createNewBook()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_shelf -> {
                showShelfSelector()
                true
            }
            R.id.action_shelves -> {
                startActivity(Intent(this, ShelfManagementActivity::class.java))
                true
            }
            R.id.action_cleanup -> {
                confirmAndCleanupSentBooks()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showShelfSelector() {
        lifecycleScope.launch {
            val shelves = database.shelfDao().getAllShelves().first()
            val items = mutableListOf<Pair<String, Long?>>()

            // Add "All Books" option
            items.add("All Books" to null)

            // Add "No Shelf" option
            items.add("No Shelf" to -1L)

            // Add shelves
            shelves.forEach { shelf ->
                items.add(shelf.name to shelf.id)
            }

            val itemNames = items.map { it.first }.toTypedArray()
            val currentIndex = items.indexOfFirst { it.second == currentShelfId }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select Shelf")
                .setSingleChoiceItems(itemNames, currentIndex) { dialog, which ->
                    val selected = items[which]
                    selectShelf(selected.second, selected.first)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun selectShelf(shelfId: Long?, shelfName: String) {
        currentShelfId = shelfId
        currentShelfName = shelfName

        // Save preference
        shelfPrefs.edit().apply {
            when (shelfId) {
                null -> putLong(KEY_SELECTED_SHELF, Long.MIN_VALUE)
                -1L -> putLong(KEY_SELECTED_SHELF, NO_SHELF_ID)
                else -> putLong(KEY_SELECTED_SHELF, shelfId)
            }
            apply()
        }

        // Update UI
        updateTitle()
        observeBooks()
    }

    private fun confirmAndCleanupSentBooks() {
        AlertDialog.Builder(this)
            .setTitle("Clean Up Sent Books")
            .setMessage("This will delete all books that have been sent to the server. This action cannot be undone. Continue?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteAllSentBooks()
                    Toast.makeText(
                        this@MainActivity,
                        "Sent books deleted successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_IS_NEW_BOOK = "is_new_book"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // SharedPreferences keys
        private const val PREFS_NAME = "shelf_prefs"
        private const val KEY_SELECTED_SHELF = "selected_shelf_id"
        private const val NO_SHELF_ID = -1L
    }
}
