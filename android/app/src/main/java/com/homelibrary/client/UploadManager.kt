package com.homelibrary.client

import android.content.Context
import android.net.Uri
import android.util.Log
import com.homelibrary.client.data.AppDatabase
import com.homelibrary.client.data.BookRepository
import com.homelibrary.client.data.BookStatus
import com.homelibrary.client.data.BookWithPages
import com.homelibrary.client.data.PageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages the asynchronous upload of books to the server via gRPC.
 * 
 * Handles concurrent uploads, tracks upload progress and status,
 * and updates the local database with upload results.
 */
class UploadManager(
    private val context: Context,
    private val repository: BookRepository,
    private val bookServiceClient: BookServiceClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _uploadProgress = MutableStateFlow<Map<Long, UploadState>>(emptyMap())
    val uploadProgress: StateFlow<Map<Long, UploadState>> = _uploadProgress

    sealed class UploadState {
        object Uploading : UploadState()
        object Success : UploadState()
        data class Error(val message: String) : UploadState()
    }

    fun uploadBook(bookWithPages: BookWithPages) {
        val bookId = bookWithPages.book.id
        Log.d(TAG, "Starting upload for book $bookId")
        
        scope.launch {
            try {
                // Update status to SENT
                repository.updateBookStatus(bookId, BookStatus.SENT)
                updateProgress(bookId, UploadState.Uploading)

                // Get cover and info page URIs
                val pages = bookWithPages.pages
                val coverPage = pages.find { it.type == PageType.COVER }
                val infoPage = pages.find { it.type == PageType.INFO_PAGE }

                if (coverPage != null && infoPage != null) {
                    val coverUri = Uri.fromFile(File(coverPage.imagePath))
                    val infoUri = Uri.fromFile(File(infoPage.imagePath))
                    Log.d(TAG, "Uploading book $bookId: cover=${coverPage.imagePath}, info=${infoPage.imagePath}")

                    val language = AppSettings.getOcrLanguage(context)
                    // Pass language to uploadBook
                    bookServiceClient.uploadBook(context, coverUri, infoUri, language).collect { result ->
                        Log.d(TAG, "Upload result for book $bookId: $result")
                        when (result) {
                            is BookServiceClient.UploadResult.Success -> {
                                repository.updateBookStatus(bookId, BookStatus.PROCESSED)
                                updateProgress(bookId, UploadState.Success)
                            }
                            is BookServiceClient.UploadResult.Error -> {
                                // Keep as SENT for retry, but report error
                                updateProgress(bookId, UploadState.Error(result.message))
                            }
                        }
                    }
                } else {
                    val missing = mutableListOf<String>()
                    if (coverPage == null) missing.add("cover")
                    if (infoPage == null) missing.add("info page")
                    updateProgress(bookId, UploadState.Error("Missing: ${missing.joinToString(", ")}"))
                }
            } catch (e: Exception) {
                updateProgress(bookId, UploadState.Error(e.message ?: "Upload failed"))
            }
        }
    }

    private fun updateProgress(bookId: Long, state: UploadState) {
        _uploadProgress.value = _uploadProgress.value + (bookId to state)
    }

    companion object {
        private const val TAG = "UploadManager"
        
        @Volatile
        private var INSTANCE: UploadManager? = null

        fun getInstance(context: Context): UploadManager {
            return INSTANCE ?: synchronized(this) {
                val database = AppDatabase.getDatabase(context)
                val imageStorageDir = File(context.filesDir, "book_images").also { it.mkdirs() }
                val repository = BookRepository(database.bookDao(), imageStorageDir)
                
                // Use host from settings
                val host = AppSettings.getServerHost(context)
                val port = AppSettings.getServerPort()
                Log.d(TAG, "Using server: $host:$port")
                val client = BookServiceClient(host, port)
                
                val instance = UploadManager(context.applicationContext, repository, client)
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Resets the singleton instance to pick up new settings.
         * Call this when server settings are changed.
         */
        fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
