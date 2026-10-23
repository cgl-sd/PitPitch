package com.example.pitpitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.app.SportsApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch window is branded; the real Activity stays white on recreation/resume.
        // Native splash removal follows the first app draw, with no timer or readiness/network hold.
        setTheme(R.style.Theme_PitPitch)
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[SportsViewModel::class.java]
        // Day/race polling is foreground-only: stop when the app goes to background, resume on return.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { model.resumeForegroundPolling() }
            override fun onStop(owner: LifecycleOwner) { model.pauseForegroundPolling() }
        })
        setContent { SportsApp(model) }
    }
}
