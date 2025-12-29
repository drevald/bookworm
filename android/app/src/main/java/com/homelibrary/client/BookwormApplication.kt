package com.homelibrary.client

import android.app.Application
import android.util.Log

class BookwormApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize OpenCV
        if (!DocumentScanner.init()) {
            Log.e("BookwormApplication", "Failed to initialize OpenCV")
        }
    }
}
