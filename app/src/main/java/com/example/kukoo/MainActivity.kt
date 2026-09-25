package com.example.kukoo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.kukoo.call.IncomingCallNotifier
import com.example.kukoo.ui.KukooRoot
import com.example.kukoo.ui.KukooViewModel
import com.example.kukoo.ui.theme.KukooTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: KukooViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only the call alarm may show this app above the lock screen, and only while the call is active.
        lifecycleScope.launch {
            viewModel.state.map { it.overLockscreen }.distinctUntilChanged().collect(::setOverLockscreen)
        }

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            KukooTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    KukooRoot(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as KukooApp).container.appVisible = true
    }

    override fun onStop() {
        (application as KukooApp).container.appVisible = false
        viewModel.onAppBackgrounded()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != IncomingCallNotifier.ACTION_INCOMING_CALL) return
        IncomingCallNotifier.cancel(this)
        viewModel.onIncomingCall(fromAlarm = true)
        // Consume it so a later recreate does not ring again.
        intent.action = null
    }

    private fun setOverLockscreen(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            @Suppress("DEPRECATION")
            if (enabled) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
