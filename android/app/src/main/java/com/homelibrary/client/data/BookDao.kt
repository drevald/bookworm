package com.homelibrary.client.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    // Book operations
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    suspend fun getAllBooksSync(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): BookEntity?

    @Insert
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET status = :status WHERE id = :bookId")
    suspend fun updateBookStatus(bookId: Long, status: BookStatus)

    @Query("UPDATE books SET shelfId = :shelfId WHERE id = :bookId")
    suspend fun updateBookShelf(bookId: Long, shelfId: Long?)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: Long)

    // Page operations
    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY sortOrder")
    fun getPagesForBook(bookId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY sortOrder")
    suspend fun getPagesForBookSync(bookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageById(pageId: Long): PageEntity?

    @Insert
    suspend fun insertPage(page: PageEntity): Long

    @Update
    suspend fun updatePage(page: PageEntity)

    @Delete
    suspend fun deletePage(page: PageEntity)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePageById(pageId: Long)

    @Query("SELECT MAX(sortOrder) FROM pages WHERE bookId = :bookId")
    suspend fun getMaxSortOrder(bookId: Long): Int?

    // Book with pages
    @Transaction
    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookWithPages(bookId: Long): BookWithPages?

    @Transaction
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooksWithPages(): Flow<List<BookWithPages>>

    @Transaction
    @Query("SELECT * FROM books WHERE shelfId = :shelfId ORDER BY createdAt DESC")
    fun getBooksByShelf(shelfId: Long): Flow<List<BookWithPages>>

    @Transaction
    @Query("SELECT * FROM books WHERE shelfId IS NULL ORDER BY createdAt DESC")
    fun getBooksWithoutShelf(): Flow<List<BookWithPages>>
}
