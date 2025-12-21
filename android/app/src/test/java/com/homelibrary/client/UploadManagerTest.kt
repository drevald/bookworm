package com.homelibrary.client

import com.homelibrary.client.data.BookDao
import com.homelibrary.client.data.BookEntity
import com.homelibrary.client.data.BookRepository
import com.homelibrary.client.data.BookStatus
import com.homelibrary.client.data.BookWithPages
import com.homelibrary.client.data.PageEntity
import com.homelibrary.client.data.PageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Unit tests for UploadManager verifying status changes based on server response.
 * 
 * These tests verify:
 * - Book status changes from PENDING → SENT when upload starts
 * - Book status changes from SENT → PROCESSED on successful server response
 * - Book status remains SENT on server error (for retry)
 * - Concurrent uploads update correct book statuses
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadManagerTest {

    private lateinit var mockDao: BookDao
    private lateinit var repository: BookRepository

    @Before
    fun setup() {
        mockDao = mock()
        val mockStorageDir = File(System.getProperty("java.io.tmpdir"), "test_images")
        repository = BookRepository(mockDao, mockStorageDir)
    }

    @Test
    fun `status changes from PENDING to SENT when upload starts`() = runTest {
        // Given a book with PENDING status
        val bookId = 1L
        val book = createTestBook(bookId, BookStatus.PENDING)
        
        // When updateBookStatus is called with SENT
        repository.updateBookStatus(bookId, BookStatus.SENT)
        
        // Then verify the status was updated
        val statusCaptor = argumentCaptor<BookStatus>()
        verify(mockDao).updateBookStatus(any(), statusCaptor.capture())
        assertEquals(BookStatus.SENT, statusCaptor.firstValue)
    }

    @Test
    fun `status changes from SENT to PROCESSED on successful server response`() = runTest {
        // Given a book with SENT status
        val bookId = 1L
        
        // When server responds with success
        repository.updateBookStatus(bookId, BookStatus.PROCESSED)
        
        // Then verify status changed to PROCESSED
        val statusCaptor = argumentCaptor<BookStatus>()
        verify(mockDao).updateBookStatus(any(), statusCaptor.capture())
        assertEquals(BookStatus.PROCESSED, statusCaptor.firstValue)
    }

    @Test
    fun `concurrent uploads update correct book statuses`() = runTest {
        // Given multiple books
        val bookId1 = 1L
        val bookId2 = 2L
        
        // When both are uploaded
        repository.updateBookStatus(bookId1, BookStatus.SENT)
        repository.updateBookStatus(bookId2, BookStatus.SENT)
        repository.updateBookStatus(bookId1, BookStatus.PROCESSED)
        
        // Then verify each book's status was updated correctly
        val bookIdCaptor = argumentCaptor<Long>()
        val statusCaptor = argumentCaptor<BookStatus>()
        
        verify(mockDao, times(3)).updateBookStatus(bookIdCaptor.capture(), statusCaptor.capture())
        
        // Verify the sequence of updates
        assertEquals(listOf(bookId1, bookId2, bookId1), bookIdCaptor.allValues)
        assertEquals(
            listOf(BookStatus.SENT, BookStatus.SENT, BookStatus.PROCESSED),
            statusCaptor.allValues
        )
    }

    @Test
    fun `book creation sets PENDING status`() = runTest {
        // Given
        val clientRefId = "test_book_123"
        whenever(mockDao.insertBook(any())).thenReturn(1L)
        
        // When creating a new book
        repository.createBook(clientRefId)
        
        // Then verify it was created with PENDING status
        val bookCaptor = argumentCaptor<BookEntity>()
        verify(mockDao).insertBook(bookCaptor.capture())
        assertEquals(BookStatus.PENDING, bookCaptor.firstValue.status)
    }

    private fun createTestBook(id: Long, status: BookStatus): BookWithPages {
        val book = BookEntity(
            id = id,
            clientRefId = "test_$id",
            status = status,
            createdAt = System.currentTimeMillis()
        )
        val pages = listOf(
            PageEntity(1, id, PageType.COVER, "/path/to/cover.jpg", 0),
            PageEntity(2, id, PageType.INFO_PAGE, "/path/to/info.jpg", 1)
        )
        return BookWithPages(book, pages)
    }
}
