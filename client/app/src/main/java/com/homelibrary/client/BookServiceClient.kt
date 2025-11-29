package com.homelibrary.client

import android.content.Context
import android.net.Uri
import com.google.protobuf.ByteString
import com.homelibrary.api.BookMetadata
import com.homelibrary.api.BookServiceGrpc
import com.homelibrary.api.ImageChunk
import com.homelibrary.api.ImageType
import com.homelibrary.api.UploadBookRequest
import com.homelibrary.api.UploadBookResponse
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BookServiceClient(private val host: String, private val port: Int) {

    private val channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build()

    private val stub = BookServiceGrpc.newStub(channel)

    fun uploadBook(
        context: Context,
        coverUri: Uri,
        backUri: Uri,
        infoUri: Uri
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

        val requestObserver = stub.uploadBook(responseObserver)

        try {
            // 1. Send Metadata (optional, can be empty for now)
            requestObserver.onNext(
                UploadBookRequest.newBuilder()
                    .setMetadata(BookMetadata.newBuilder().setClientRefId("android_client").build())
                    .build()
            )

            // 2. Send Images
            sendImage(context, coverUri, ImageType.COVER, requestObserver)
            sendImage(context, backUri, ImageType.BACK, requestObserver)
            sendImage(context, infoUri, ImageType.INFO_PAGE, requestObserver)

            requestObserver.onCompleted()

        } catch (e: Exception) {
            requestObserver.onError(e)
            trySend(UploadResult.Error(e.message ?: "Upload failed"))
            close()
        }

        awaitClose { 
            // Cleanup if needed
        }
    }

    private fun sendImage(
        context: Context,
        uri: Uri,
        type: ImageType,
        observer: StreamObserver<UploadBookRequest>
    ) {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        inputStream?.use { stream ->
            val buffer = ByteArray(1024 * 16) // 16KB chunks
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = ImageChunk.newBuilder()
                    .setType(type)
                    .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                    .build()
                
                observer.onNext(
                    UploadBookRequest.newBuilder()
                        .setImageChunk(chunk)
                        .build()
                )
            }
        }
    }

    sealed class UploadResult {
        data class Success(val response: UploadBookResponse) : UploadResult()
        data class Error(val message: String) : UploadResult()
    }
}
