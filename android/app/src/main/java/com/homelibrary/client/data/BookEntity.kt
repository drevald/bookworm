package com.homelibrary.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientRefId: String,
    val status: BookStatus,
    val shelfId: Long? = null,
    val createdAt: Long
)

enum class BookStatus {
    PENDING,    // Clock icon - waiting to be delivered
    SENT,       // Grey tick - just sent
    PROCESSED   // Green tick - sent and processed successfully
}
