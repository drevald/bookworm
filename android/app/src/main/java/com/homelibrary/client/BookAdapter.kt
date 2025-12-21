package com.homelibrary.client

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homelibrary.client.data.BookStatus
import com.homelibrary.client.data.BookWithPages
import com.homelibrary.client.databinding.ItemBookBinding

class BookAdapter(
    private val onBookClick: (BookWithPages) -> Unit
) : ListAdapter<BookWithPages, BookAdapter.BookViewHolder>(BookDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookViewHolder(
        private val binding: ItemBookBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bookWithPages: BookWithPages) {
            val book = bookWithPages.book
            val pages = bookWithPages.pages

            // Set status icon
            val statusIcon = when (book.status) {
                BookStatus.PROCESSED -> R.drawable.ic_status_processed
                BookStatus.SENT -> R.drawable.ic_status_sent
                BookStatus.PENDING -> R.drawable.ic_status_pending
            }
            binding.statusIcon.setImageResource(statusIcon)

            // Clear and populate thumbnails
            binding.thumbnailsContainer.removeAllViews()
            val context = binding.root.context
            val thumbnailSize = (80 * context.resources.displayMetrics.density).toInt()
            val marginSize = (4 * context.resources.displayMetrics.density).toInt()

            pages.forEach { page ->
                val imageView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbnailSize, thumbnailSize).apply {
                        marginEnd = marginSize
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(android.R.color.darker_gray)
                    
                    // Load thumbnail from file
                    try {
                        val bitmap = BitmapFactory.decodeFile(page.imagePath)
                        setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        // Keep default background if loading fails
                    }
                }
                binding.thumbnailsContainer.addView(imageView)
            }

            // If no pages, add a placeholder
            if (pages.isEmpty()) {
                val placeholder = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbnailSize, thumbnailSize)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setBackgroundResource(android.R.color.darker_gray)
                }
                binding.thumbnailsContainer.addView(placeholder)
            }

            // Click listeners
            val clickListener = { _: android.view.View -> onBookClick(bookWithPages) }
            binding.root.setOnClickListener(clickListener)
            binding.thumbnailsScrollView.setOnClickListener(clickListener)
            binding.thumbnailsContainer.setOnClickListener(clickListener)

            // Propagate click to dynamically created images
            for (i in 0 until binding.thumbnailsContainer.childCount) {
                binding.thumbnailsContainer.getChildAt(i).setOnClickListener(clickListener)
            }
        }
    }

    private class BookDiffCallback : DiffUtil.ItemCallback<BookWithPages>() {
        override fun areItemsTheSame(oldItem: BookWithPages, newItem: BookWithPages): Boolean {
            return oldItem.book.id == newItem.book.id
        }

        override fun areContentsTheSame(oldItem: BookWithPages, newItem: BookWithPages): Boolean {
            return oldItem == newItem
        }
    }
}
