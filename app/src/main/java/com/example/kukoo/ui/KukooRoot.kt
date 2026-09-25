package com.example.kukoo.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.ui.call.IncomingCallScreen
import com.example.kukoo.ui.home.DailyCallDialog
import com.example.kukoo.ui.home.HomeScreen
import com.example.kukoo.ui.home.TaskEditorSheet
import com.example.kukoo.ui.plan.PlanScreen
import com.example.kukoo.ui.session.VoiceSessionScreen

/** Switches between the four screens (Home, Incoming Call, Voice Session, Plan Result). */
@Composable
fun KukooRoot(vm: KukooViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !vm.canNotify()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showDailyDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.screen != Screen.HOME) { vm.onBack() }

    Crossfade(targetState = state.screen, label = "screen") { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                format = vm.format,
                clock = vm.clock,
                onTalk = vm::startSession,
                onAdd = { vm.openEditor(null) },
                onEdit = { vm.openEditor(it) },
                onToggleDone = vm::toggleDone,
                onDelete = vm::deleteTask,
                onPlan = { vm.planFromHome(PlanScope.DAY) },
                onRingNow = vm::ringNow,
                onRingIn = {
                    ensureNotificationPermission()
                    vm.ringIn(10)
                },
                onDailyCall = { showDailyDialog = true },
                onResetDemo = vm::resetDemoData,
                onNoticeShown = vm::dismissNotice
            )

            Screen.INCOMING_CALL -> IncomingCallScreen(
                clock = vm.clock,
                format = vm.format,
                onAnswer = vm::answerCall,
                onDecline = vm::declineCall
            )

            Screen.SESSION -> VoiceSessionScreen(
                session = state.session,
                clock = vm.clock,
                onSend = vm::submitText,
                onMicPress = vm::onMicPress,
                onMicRelease = vm::onMicRelease,
                onEnd = vm::endCall
            )

            Screen.PLAN -> state.plan?.let { plan ->
                PlanScreen(
                    plan = plan,
                    format = vm.format,
                    doneLabel = if (state.planReturnsTo == Screen.SESSION) "Back to call" else "Done",
                    onBack = vm::onBack
                )
            }
        }
    }

    if (state.screen == Screen.HOME) {
        state.editor?.let { editor ->
            TaskEditorSheet(
                editor = editor,
                format = vm.format,
                clock = vm.clock,
                onSave = vm::saveEditor,
                onDismiss = vm::closeEditor
            )
        }
    }

    if (showDailyDialog) {
        DailyCallDialog(
            current = vm.dailyCallTime(),
            canUseFullScreen = vm.canUseFullScreen(),
            onOpenFullScreenSettings = {
                if (Build.VERSION.SDK_INT >= 34) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            "package:${context.packageName}".toUri()
                        )
                    )
                }
            },
            onSet = {
                ensureNotificationPermission()
                vm.setDailyCall(it)
                showDailyDialog = false
            },
            onTurnOff = {
                vm.setDailyCall(null)
                showDailyDialog = false
            },
            onDismiss = { showDailyDialog = false }
        )
    }
}
