package com.homelibrary.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val photo: ByteArray?,  // Low-res thumbnail stored in DB
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ShelfEntity
        if (id != other.id) return false
        if (name != other.name) return false
        if (photo != null) {
            if (other.photo == null) return false
            if (!photo.contentEquals(other.photo)) return false
        } else if (other.photo != null) return false
        if (createdAt != other.createdAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (photo?.contentHashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
