package com.moyeoyo.app

import DeepLinkHandler
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.moyeoyo.app.R

class MainActivity : AppCompatActivity() {

    private lateinit var deepLinkHandler: DeepLinkHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deepLinkHandler = DeepLinkHandler(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        deepLinkHandler.handleIntent(intent)
    }
}
