package com.homelibrary.client

import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homelibrary.client.data.PageEntity
import com.homelibrary.client.data.PageType
import com.homelibrary.client.databinding.ItemPageBinding
import java.io.File

class PageAdapter(
    private val onRetakeClick: (PageEntity) -> Unit,
    private val onDeleteClick: (PageEntity) -> Unit,
    private val onChangeTypeClick: (PageEntity) -> Unit,
    private val onImageClick: (PageEntity) -> Unit
) : ListAdapter<PageEntity, PageAdapter.PageViewHolder>(PageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PageViewHolder(
        private val binding: ItemPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(page: PageEntity) {
            // Set page type label with numbering for info pages
            binding.pageTypeLabel.text = when (page.type) {
                PageType.COVER -> "COVER"
                PageType.INFO_PAGE -> {
                    // Count which info page this is
                    val infoPageNumber = currentList
                        .filter { it.type == PageType.INFO_PAGE }
                        .indexOfFirst { it.id == page.id } + 1
                    if (infoPageNumber > 1 || currentList.count { it.type == PageType.INFO_PAGE } > 1) {
                        "INFO PAGE $infoPageNumber"
                    } else {
                        "INFO PAGE"
                    }
                }
                PageType.OTHER -> "PAGE"
            }

            // Load thumbnail from file
            try {
                val imageFile = File(page.imagePath)
                Log.d("PageAdapter", "Loading image: ${page.imagePath}, exists: ${imageFile.exists()}, size: ${imageFile.length()}")

                if (imageFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(page.imagePath)
                    if (bitmap != null) {
                        binding.pageThumbnail.setImageBitmap(bitmap)
                        Log.d("PageAdapter", "Bitmap loaded successfully: ${bitmap.width}x${bitmap.height}")
                    } else {
                        Log.e("PageAdapter", "BitmapFactory.decodeFile returned null for ${page.imagePath}")
                        binding.pageThumbnail.setImageResource(android.R.color.darker_gray)
                    }
                } else {
                    Log.e("PageAdapter", "Image file does not exist: ${page.imagePath}")
                    binding.pageThumbnail.setImageResource(android.R.color.darker_gray)
                }
            } catch (e: Exception) {
                Log.e("PageAdapter", "Error loading image: ${page.imagePath}", e)
                binding.pageThumbnail.setImageResource(android.R.color.darker_gray)
            }

            // Click listeners
            binding.pageThumbnail.setOnClickListener { onImageClick(page) }
            binding.changeTypeButton.setOnClickListener { onChangeTypeClick(page) }
            binding.retakeButton.setOnClickListener { onRetakeClick(page) }
            binding.deletePageButton.setOnClickListener { onDeleteClick(page) }
        }
    }

    private class PageDiffCallback : DiffUtil.ItemCallback<PageEntity>() {
        override fun areItemsTheSame(oldItem: PageEntity, newItem: PageEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PageEntity, newItem: PageEntity): Boolean {
            return oldItem == newItem
        }
    }
}
