package com.homelibrary.client

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.protobuf.ByteString
import com.homelibrary.api.BookMetadata
import com.homelibrary.api.BookServiceGrpc
import com.homelibrary.api.ImageType
import com.homelibrary.api.PageImage
import com.homelibrary.api.UploadBookRequest
import com.homelibrary.api.UploadBookResponse
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * gRPC client for communicating with the BookService server.
 * 
 * Handles book uploads by streaming cover and info page images
 * to the server using bi-directional gRPC streaming.
 * 
 * @param host Server hostname (10.0.2.2 for emulator, actual IP for real devices)
 * @param port gRPC server port (default: 9090)
 */
class BookServiceClient(private val host: String, private val port: Int) {

    private val channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .maxInboundMessageSize(32 * 1024 * 1024)  // 32 MB for receiving responses
        .maxInboundMetadataSize(8 * 1024 * 1024)  // 8 MB for metadata
        .build()

    private val stub = BookServiceGrpc.newStub(channel)
    
    init {
        Log.d(TAG, "BookServiceClient initialized: $host:$port")
    }
    
    companion object {
        private const val TAG = "BookServiceClient"
    }

    fun uploadBook(
        context: Context,
        coverUri: Uri,
        infoUris: List<Uri>,
        language: String = "rus"
    ): Flow<UploadResult> = callbackFlow {

        val responseObserver = object : StreamObserver<UploadBookResponse> {
            override fun onNext(value: UploadBookResponse) {
                if (value.success) {
                    trySend(UploadResult.Success(value))
                } else {
                    trySend(UploadResult.Error(value.errorMessage))
                }
            }

            override fun onError(t: Throwable) {
                trySend(UploadResult.Error(t.message ?: "Unknown error"))
                close()
            }

            override fun onCompleted() {
                close()
            }
        }

        try {
            Log.d(TAG, "Uploading cover and ${infoUris.size} info page(s)")

            // Build list of PageImages
            val pageImages = mutableListOf<PageImage>()

            // Add all info pages
            infoUris.forEachIndexed { index, infoUri ->
                Log.d(TAG, "Loading info page ${index + 1}/${infoUris.size}")
                val pageImage = loadImage(context, infoUri, ImageType.INFO_PAGE)
                pageImages.add(pageImage)
            }

            // Add cover
            val coverImage = loadImage(context, coverUri, ImageType.COVER)
            pageImages.add(coverImage)

            // Build request
            val request = UploadBookRequest.newBuilder()
                .setMetadata(
                    BookMetadata.newBuilder()
                        .setClientRefId("android_client")
                        .setLanguage(language)
                        .build()
                )
                .addAllImages(pageImages)
                .build()

            Log.d(TAG, "Sending request with ${pageImages.size} images")

            // Make unary call
            stub.uploadBook(request, responseObserver)

        } catch (e: Exception) {
            trySend(UploadResult.Error(e.message ?: "Upload failed"))
            close()
        }

        awaitClose {
            // Cleanup if needed
        }
    }

    private fun loadImage(
        context: Context,
        uri: Uri,
        type: ImageType
    ): PageImage {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open input stream for URI: $uri")

        inputStream.use { stream ->
            val imageData = stream.readBytes()
            Log.i(TAG, "[IMAGE_SIZE] Loaded image - Type: $type, Total bytes: ${imageData.size}")

            return PageImage.newBuilder()
                .setType(type)
                .setData(ByteString.copyFrom(imageData))
                .build()
        }
    }

    sealed class UploadResult {
        data class Success(val response: UploadBookResponse) : UploadResult()
        data class Error(val message: String) : UploadResult()
    }
}
