package com.homelibrary.client

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homelibrary.client.data.PageEntity
import com.homelibrary.client.data.PageType
import com.homelibrary.client.databinding.ItemPageBinding

class PageAdapter(
    private val onRetakeClick: (PageEntity) -> Unit,
    private val onDeleteClick: (PageEntity) -> Unit
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
                val bitmap = BitmapFactory.decodeFile(page.imagePath)
                binding.pageThumbnail.setImageBitmap(bitmap)
            } catch (e: Exception) {
                binding.pageThumbnail.setImageResource(android.R.color.darker_gray)
            }

            // Click listeners
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
