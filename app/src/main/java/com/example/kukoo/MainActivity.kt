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
import com.example.kukoo.call.BatteryOptimization
import com.example.kukoo.call.CallAlarmReceiver
import com.example.kukoo.call.CallForegroundService
import com.example.kukoo.call.IncomingCallNotifier
import com.example.kukoo.ui.KukooRoot
import com.example.kukoo.ui.Screen
import com.example.kukoo.ui.KukooViewModel
import com.example.kukoo.ui.theme.KukooTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.content.Context
import android.app.AlarmManager

class MainActivity : ComponentActivity() {
    private val viewModel: KukooViewModel by viewModels()
    private var sessionService = false
    private var batteryDialog: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms()
        // Before anything else, so a call launched onto a locked screen is not stopped by the keyguard.
        // (The ViewModel is not touched yet: it must not be created before super.onCreate.)
        if (intent?.action == IncomingCallNotifier.ACTION_INCOMING_CALL) setOverLockscreen(true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set the ViewModel state first so the collectors below never see a stale "not over lockscreen".
        if (savedInstanceState == null) handleIntent(intent)

        // The manifest allows showing when locked (so the OS applies it at launch); at runtime it is only
        // kept while a call started by the alarm is active, and released when the app returns Home.
        lifecycleScope.launch {
            viewModel.state.map { it.overLockscreen }.distinctUntilChanged().collect(::setOverLockscreen)
        }
        // Keep the display on while the call rings and through the whole voice session.
        lifecycleScope.launch {
            viewModel.state.map { it.overLockscreen || it.screen == Screen.INCOMING_CALL || it.screen == Screen.SESSION }
                .distinctUntilChanged().collect(::setKeepScreenOn)
        }

        // A running voice session is a foreground service so OEM task killers leave it alone.
        lifecycleScope.launch {
            viewModel.state.map { it.screen == Screen.SESSION }.distinctUntilChanged().collect { inSession ->
                if (inSession) {
                    sessionService = CallForegroundService.startSession(this@MainActivity)
                } else if (sessionService) {
                    sessionService = false
                    CallForegroundService.stop(this@MainActivity)
                }
            }
        }

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
        if (intent.action == IncomingCallNotifier.ACTION_INCOMING_CALL || viewModel.state.value.overLockscreen) {
            setOverLockscreen(true)
        }
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as KukooApp).container.appVisible = true
        viewModel.onAppForegrounded()
        maybeAskBatteryWhitelist()
    }

    /** Offers the battery-optimization waiver from Home only, never on top of a ringing call. */
    private fun maybeAskBatteryWhitelist() {
        val s = viewModel.state.value
        if (batteryDialog?.isShowing == true || s.screen != Screen.HOME || s.overLockscreen) return
        val now = (application as KukooApp).container.clock.millis()
        if (!BatteryOptimization.shouldAsk(this, now)) return
        batteryDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Keep the daily call reliable")
            .setMessage(
                "Some phones pause apps in the background, which can delay or drop the Your Tasks call. " +
                    "Allow Kukoo to ignore battery optimization so it rings on time."
            )
            .setPositiveButton("Allow") { _, _ -> BatteryOptimization.request(this) }
            .setNegativeButton("Not now") { _, _ -> BatteryOptimization.rememberDeclined(this, now) }
            .show()
    }

    override fun onDestroy() {
        batteryDialog?.dismiss()
        super.onDestroy()
    }

    override fun onStop() {
        (application as KukooApp).container.appVisible = false
        viewModel.onAppBackgrounded()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != IncomingCallNotifier.ACTION_INCOMING_CALL) return
        IncomingCallNotifier.cancel(this)
        if (sessionService) CallForegroundService.startSession(this) // cancel() also stops the service
        viewModel.onIncomingCall(
            fromAlarm = true,
            taskId = intent.getLongExtra(CallAlarmReceiver.EXTRA_TASK_ID, CallAlarmReceiver.NO_TASK)
        )
        // Consume it so a later recreate does not ring again.
        intent.action = null
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
