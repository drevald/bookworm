package com.homelibrary.client

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
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

class ShelfManagementActivity : AppCompatActivity() {

    private lateinit var shelvesRecyclerView: RecyclerView
    private lateinit var emptyStateText: android.widget.TextView
    private lateinit var addShelfFab: FloatingActionButton
    private lateinit var shelfAdapter: ShelfAdapter
    private lateinit var database: AppDatabase

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
        val input = EditText(this).apply {
            hint = "Shelf name"
        }

        AlertDialog.Builder(this)
            .setTitle("Add Shelf")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val shelfName = input.text.toString().trim()
                if (shelfName.isNotEmpty()) {
                    addShelf(shelfName)
                } else {
                    Toast.makeText(this, "Shelf name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addShelf(name: String) {
        lifecycleScope.launch {
            val shelf = ShelfEntity(
                name = name,
                photoPath = null,
                createdAt = System.currentTimeMillis()
            )
            database.shelfDao().insertShelf(shelf)
            Toast.makeText(this@ShelfManagementActivity, "Shelf added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun editShelf(shelf: ShelfEntity) {
        val input = EditText(this).apply {
            setText(shelf.name)
            hint = "Shelf name"
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Shelf")
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
