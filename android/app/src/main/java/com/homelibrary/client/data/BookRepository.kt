package com.homelibrary.client.data

import kotlinx.coroutines.flow.Flow
import java.io.File

class BookRepository(
    private val bookDao: BookDao,
    private val imageStorageDir: File
) {
    // Book operations
    fun getAllBooks(): Flow<List<BookEntity>> = bookDao.getAllBooks()
    
    fun getAllBooksWithPages(): Flow<List<BookWithPages>> = bookDao.getAllBooksWithPages()

    suspend fun getBookById(bookId: Long): BookEntity? = bookDao.getBookById(bookId)

    suspend fun getBookWithPages(bookId: Long): BookWithPages? = bookDao.getBookWithPages(bookId)

    suspend fun createBook(clientRefId: String): Long {
        val book = BookEntity(
            clientRefId = clientRefId,
            status = BookStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        return bookDao.insertBook(book)
    }

    suspend fun updateBookStatus(bookId: Long, status: BookStatus) {
        bookDao.updateBookStatus(bookId, status)
    }

    suspend fun deleteBook(bookId: Long) {
        // Delete image files first
        val pages = bookDao.getPagesForBookSync(bookId)
        pages.forEach { page ->
            File(page.imagePath).delete()
        }
        bookDao.deleteBookById(bookId)
    }

    suspend fun deleteAllSentBooks() {
        // Get all books with SENT or PROCESSED status
        val allBooks = bookDao.getAllBooksSync()
        val sentBooks = allBooks.filter {
            it.status == BookStatus.SENT || it.status == BookStatus.PROCESSED
        }

        // Delete each sent book
        sentBooks.forEach { book ->
            deleteBook(book.id)
        }
    }

    // Page operations
    fun getPagesForBook(bookId: Long): Flow<List<PageEntity>> = bookDao.getPagesForBook(bookId)

    suspend fun addPage(bookId: Long, type: PageType, imageFile: File): Long {
        // Move/copy image to storage directory
        val targetFile = File(imageStorageDir, "${System.currentTimeMillis()}_${type.name}.jpg")
        imageFile.copyTo(targetFile, overwrite = true)
        
        val sortOrder = (bookDao.getMaxSortOrder(bookId) ?: -1) + 1
        val page = PageEntity(
            bookId = bookId,
            type = type,
            imagePath = targetFile.absolutePath,
            sortOrder = sortOrder
        )
        return bookDao.insertPage(page)
    }

    suspend fun deletePage(pageId: Long, imagePath: String) {
        File(imagePath).delete()
        bookDao.deletePageById(pageId)
    }

    suspend fun updatePageType(pageId: Long, newType: PageType) {
        val page = bookDao.getPageById(pageId)
        page?.let {
            bookDao.updatePage(it.copy(type = newType))
        }
    }

    suspend fun retakePage(pageId: Long, oldImagePath: String, newImageFile: File) {
        // Delete old image
        File(oldImagePath).delete()
        
        // Copy new image
        val targetFile = File(imageStorageDir, "${System.currentTimeMillis()}_retake.jpg")
        newImageFile.copyTo(targetFile, overwrite = true)
        
        // Update page with new path
        val page = bookDao.getPagesForBookSync(0).find { it.id == pageId }
        page?.let {
            bookDao.updatePage(it.copy(imagePath = targetFile.absolutePath))
        }
    }
}
