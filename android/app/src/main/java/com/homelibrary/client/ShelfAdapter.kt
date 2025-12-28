package com.homelibrary.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.homelibrary.client.data.ShelfEntity

class ShelfAdapter(
    private val onShelfClick: (ShelfEntity) -> Unit,
    private val onShelfLongClick: (ShelfEntity) -> Unit
) : ListAdapter<ShelfEntity, ShelfAdapter.ShelfViewHolder>(ShelfDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shelf, parent, false)
        return ShelfViewHolder(view, onShelfClick, onShelfLongClick)
    }

    override fun onBindViewHolder(holder: ShelfViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ShelfViewHolder(
        itemView: View,
        private val onShelfClick: (ShelfEntity) -> Unit,
        private val onShelfLongClick: (ShelfEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val shelfNameText: TextView = itemView.findViewById(R.id.shelfNameText)
        private val bookCountText: TextView = itemView.findViewById(R.id.bookCountText)
        private val shelfPhotoImageView: ImageView = itemView.findViewById(R.id.shelfPhotoImageView)
        private val deleteShelfButton: ImageButton = itemView.findViewById(R.id.deleteShelfButton)

        fun bind(shelf: ShelfEntity) {
            shelfNameText.text = shelf.name
            bookCountText.text = "0 books" // TODO: Implement book count

            // Load shelf photo if available
            if (shelf.photo != null && shelf.photo.isNotEmpty()) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                        shelf.photo, 0, shelf.photo.size
                    )
                    if (bitmap != null) {
                        shelfPhotoImageView.setImageBitmap(bitmap)
                    } else {
                        shelfPhotoImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } catch (e: Exception) {
                    shelfPhotoImageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else {
                shelfPhotoImageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            itemView.setOnClickListener {
                onShelfClick(shelf)
            }

            deleteShelfButton.setOnClickListener {
                onShelfLongClick(shelf)
            }
        }
    }

    private class ShelfDiffCallback : DiffUtil.ItemCallback<ShelfEntity>() {
        override fun areItemsTheSame(oldItem: ShelfEntity, newItem: ShelfEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ShelfEntity, newItem: ShelfEntity): Boolean {
            return oldItem == newItem
        }
    }
}
