package com.valdr.bookworm;

import android.graphics.Bitmap
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.robolectric.annotation.Config
import io.mockk.mockkStatic
import io.mockk.slot

@Config(sdk = [30]) // Set the API level for Robolectric
class MainActivityTest {

    private lateinit var activity: MainActivity
    private lateinit var recognizerMock: TextRecognizer

    @BeforeEach
    fun setUp() {
        // Initialize your activity
        activity = MainActivity()

        // Mock TextRecognizer
        recognizerMock = mockk()
        mockkStatic(TextRecognition::class)
        every { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) } returns recognizerMock
    }

    @Test
    fun testProcessImage() {
        // Arrange
        val bitmapMock = mockk<Bitmap>()
        val inputImageSlot = slot<InputImage>()
        val visionTextMock = mockk<Text>()
        val isbnText = "Sample text with ISBN: 978-3-16-148410-0"

        // Simulate successful text recognition
        every { visionTextMock.text } returns isbnText
        every { recognizerMock.process(capture(inputImageSlot)) } returns mockk {
            val taskMock = mockk<Task<Text>>()
            every { taskMock.addOnSuccessListener(any()) } answers {
                val listener = firstArg<OnSuccessListener<Text>>()
                listener.onSuccess(visionTextMock)
                taskMock
            }
            every { taskMock.addOnFailureListener(any()) } answers {
                taskMock // Do nothing for failures
            }
        }

        // Act
        val result = activity.processImage(bitmapMock)

        // Assert
        assert(result.contains("978-3-16-148410-0")) { "Expected ISBN not found in result" }
    }


    @Test
    fun testFailImage() {
        // Arrange
        val bitmapMock = mockk<Bitmap>()
        val inputImageSlot = slot<InputImage>()
        val errorMessage = "Recognition failed"

        // Simulate failure in text recognition
        every { recognizerMock.process(capture(inputImageSlot)) } returns mockk {
            val taskMock = mockk<Task<Text>>()
            every { taskMock.addOnFailureListener(any()) } answers {
                taskMock // Explicitly return the mocked Task
            }
        }

        // Act
        val result = activity.processImage(bitmapMock)

        // Assert
        assert(result.isEmpty()) { "Expected result to be empty on failure" }
    }

}
