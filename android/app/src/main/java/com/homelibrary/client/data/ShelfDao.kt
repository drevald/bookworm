package com.homelibrary.client.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelves ORDER BY name ASC")
    fun getAllShelves(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE id = :shelfId")
    suspend fun getShelfById(shelfId: Long): ShelfEntity?

    @Insert
    suspend fun insertShelf(shelf: ShelfEntity): Long

    @Update
    suspend fun updateShelf(shelf: ShelfEntity)

    @Query("DELETE FROM shelves WHERE id = :shelfId")
    suspend fun deleteShelfById(shelfId: Long)
}
