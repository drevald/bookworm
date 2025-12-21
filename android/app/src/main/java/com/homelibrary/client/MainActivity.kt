package com.homelibrary.client

import android.Manifest
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
import com.homelibrary.client.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        val database = AppDatabase.getDatabase(this)
        val imageStorageDir = File(filesDir, "book_images").also { it.mkdirs() }
        repository = BookRepository(database.bookDao(), imageStorageDir)

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

        // Observe books
        lifecycleScope.launch {
            repository.getAllBooksWithPages().collectLatest { books ->
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

    private fun createNewBook() {
        lifecycleScope.launch {
            val clientRefId = "book_${System.currentTimeMillis()}"
            val bookId = repository.createBook(clientRefId)
            
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
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_IS_NEW_BOOK = "is_new_book"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
