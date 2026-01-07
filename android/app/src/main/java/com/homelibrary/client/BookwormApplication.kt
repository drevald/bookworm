package com.homelibrary.client

import android.app.Application
import android.util.Log

class BookwormApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize OpenCV
        // TODO: Re-enable when OpenCV dependency is fixed
        // if (!DocumentScanner.init()) {
        //     Log.e("BookwormApplication", "Failed to initialize OpenCV")
        // }
    }
}
