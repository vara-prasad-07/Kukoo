package com.example.kukoo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kukoo.KukooApp
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.call.CallRinger
import com.example.kukoo.call.IncomingCallNotifier
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.EngineResult
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Plan
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import com.example.kukoo.domain.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

/**
 * Owns all UI state. Every task change (form, tap, voice) is a [TaskCommand] executed by the
 * engine, so validation and confirmation wording are identical everywhere.
 */
class KukooViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KukooApp).container
    private val engine = container.engine
    private val ringer = CallRinger(application)
    private var ringTimeout: Job? = null

    val format: TimeFormat get() = engine.format
    val clock: Clock get() = container.clock

    private val _state = MutableStateFlow(AppState(nextCallAt = container.scheduler.nextDailyAt()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var turnJob: Job? = null
    private var nextTurnId = 1L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.seedOnFirstRun() }
            safely { reloadTasks() }
        }
        viewModelScope.launch {
            container.incomingCalls.collect { onIncomingCall(fromAlarm = true) }
        }
    }

    // ---- navigation ----------------------------------------------------------------------

    /** Returns true when the back press was handled (false on Home: let the system leave the app). */
    fun onBack(): Boolean {
        when (_state.value.screen) {
            Screen.HOME -> return false
            Screen.INCOMING_CALL, Screen.SESSION -> endToHome()
            Screen.PLAN -> _state.update { it.copy(screen = it.planReturnsTo) }
        }
        return true
    }

    fun onAppBackgrounded() {
        // Leaving the app while it rings counts as a missed call. Over the lock screen the keyguard can
        // stop the activity as the call appears, so that must not drop it; the ring timeout still ends it.
        val s = _state.value
        if (s.screen == Screen.INCOMING_CALL && !s.overLockscreen) endToHome()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun notify(message: String) = _state.update { it.copy(notice = message) }

    // ---- tasks (Home) --------------------------------------------------------------------

    fun toggleDone(task: Task) {
        val ref = TaskRef.ById(task.id)
        viewModelScope.launch {
            val result = run(if (task.isDone) TaskCommand.ReopenTask(ref) else TaskCommand.CompleteTask(ref))
            if (!result.isSuccess) notify(result.spoken)
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch { notify(run(TaskCommand.DeleteTask(TaskRef.ById(task.id))).spoken) }
    }

    /** Quick in-line time change from a task card; the task keeps its date (a passed time rolls to tomorrow). */
    fun setTaskTime(task: Task, hour: Int, minute: Int) {
        val patch = TaskPatch(deadline = DeadlineSpec.Relative(time = LocalTime.of(hour, minute)))
        viewModelScope.launch {
            val result = run(TaskCommand.UpdateTask(TaskRef.ById(task.id), patch))
            if (!result.isSuccess) notify(result.spoken)
        }
    }

    /** Runs [command] from the UI (the snackbar's Undo). In a call the reply joins the transcript. */
    fun executeCommand(command: TaskCommand) {
        viewModelScope.launch {
            val result = run(command)
            if (_state.value.screen == Screen.SESSION) addTurn(fromUser = false, text = result.spoken, outcome = result.outcome)
            else notify(result.spoken)
        }
    }

    fun openEditor(task: Task?) = _state.update { it.copy(editor = EditorState(task)) }

    fun closeEditor() = _state.update { it.copy(editor = null) }

    fun saveEditor(
        title: String,
        deadline: Long?,
        durationMin: Int,
        priority: Priority,
        notes: String? = null,
        recurrence: Recurrence = Recurrence.NONE
    ) {
        val editing = _state.value.editor?.task
        val spec = deadline?.let { DeadlineSpec.Exact(it) }
        val command = if (editing == null) {
            TaskCommand.AddTask(title, spec, durationMin, priority, recurrence, notes)
        } else {
            TaskCommand.UpdateTask(
                TaskRef.ById(editing.id),
                TaskPatch(
                    title = title,
                    deadline = spec,
                    clearDeadline = spec == null,
                    durationMin = durationMin,
                    priority = priority,
                    recurrence = recurrence,
                    // Blank notes clear them (a null patch field would mean "leave as is").
                    notes = notes ?: ""
                )
            )
        }
        viewModelScope.launch {
            val result = run(command)
            if (result.isSuccess) {
                closeEditor()
            } else {
                _state.update { it.copy(editor = it.editor?.copy(error = result.spoken)) }
            }
        }
    }

    fun planFromHome(scope: PlanScope) {
        viewModelScope.launch {
            val result = run(TaskCommand.Replan(scope))
            val plan = result.plan
            if (plan != null) showPlan(plan, returnTo = Screen.HOME) else notify(result.spoken)
        }
    }

    fun resetDemoData() {
        viewModelScope.launch(Dispatchers.IO) {
            safely {
                container.resetDemoData()
                engine.resetContext()
                _state.update { it.copy(canUndo = false) }
                reloadTasks()
                notify("Demo tasks reset.")
            }
        }
    }

    // ---- call trigger --------------------------------------------------------------------

    fun dailyCallTime(): LocalTime? = container.scheduler.dailyTime()

    fun setDailyCall(time: LocalTime?) {
        if (time == null) container.scheduler.cancelDaily() else container.scheduler.scheduleDaily(time)
        _state.update { it.copy(nextCallAt = container.scheduler.nextDailyAt()) }
        notify(if (time == null) "Daily call turned off." else "Daily call set for ${format.clockTime(time)}.")
    }

    fun ringIn(seconds: Int) {
        container.scheduler.ringIn(seconds)
        notify("Ringing in $seconds seconds. Lock the screen to see it ring over the lock screen.")
    }

    fun ringNow() = onIncomingCall(fromAlarm = false)

    fun canNotify(): Boolean = IncomingCallNotifier.canNotify(getApplication())

    fun canUseFullScreen(): Boolean = IncomingCallNotifier.canUseFullScreen(getApplication())

    /** Called by the activity when it was opened by the call notification, and by the alarm when the app is visible. */
    fun onIncomingCall(fromAlarm: Boolean) {
        val current = _state.value.screen
        if (current == Screen.SESSION || current == Screen.INCOMING_CALL) return
        _state.update { it.copy(screen = Screen.INCOMING_CALL, overLockscreen = fromAlarm, editor = null) }
        ringer.start()
        ringTimeout?.cancel()
        ringTimeout = viewModelScope.launch {
            delay(RING_TIMEOUT_MS)
            if (_state.value.screen == Screen.INCOMING_CALL) endToHome()
        }
    }

    /** Declined or timed out (30s): silence the ringer and go back Home. */
    fun declineCall() = endToHome()

    /** "Snooze 15m" on the ringing call: push today's tasks back 15 minutes and drop the call. */
    fun snoozeCall() {
        ringTimeout?.cancel()
        ringer.stop()
        viewModelScope.launch {
            safely { run(TaskCommand.Snooze(SNOOZE_MINUTES)) }
            endToHome()
        }
    }

    /** Silence the ringer (and release audio focus) before the session starts speaking/listening. */
    fun answerCall() {
        ringer.stop()
        beginSession()
    }

    /** The user opened the assistant themselves (Talk button): same session, no ringing. */
    fun startSession() = beginSession()

    // ---- voice session -------------------------------------------------------------------

    private fun beginSession() {
        _state.update { it.copy(canUndo = false) }
        ringTimeout?.cancel()
        ringer.stop()
        turnJob?.cancel()
        engine.resetContext()
        _state.update {
            it.copy(
                screen = Screen.SESSION,
                session = SessionState(startedAt = container.clock.millis(), micReady = container.stt.isReady),
                plan = null,
                editor = null
            )
        }
        runTurn {
            val briefing = withContext(Dispatchers.IO) { engine.briefing() }
            assistantSays(briefing, outcome = null)
        }
    }

    fun endCall() = endToHome()

    private fun endToHome() {
        _state.update { it.copy(canUndo = false) }
        ringTimeout?.cancel()
        ringer.stop()
        turnJob?.cancel()
        container.speaker.stop()
        engine.resetContext()
        _state.update {
            it.copy(screen = Screen.HOME, session = SessionState(), plan = null, overLockscreen = false)
        }
        viewModelScope.launch { safely { reloadTasksAsync() } }
    }

    /** Typed text and tapped suggestions take the same path as a spoken transcript. */
    fun submitText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || !acceptingInput()) return
        interruptSpeech()
        runTurn { handleUtterance(text) }
    }

    fun onMicPress() {
        if (!container.stt.isReady) {
            setHint("Voice input isn't installed yet. Type below or tap a suggestion.")
            return
        }
        if (!acceptingInput()) return
        interruptSpeech()
        setPhase(Phase.LISTENING)
        runTurn {
            try {
                container.stt.startListening()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setPhase(Phase.IDLE)
                setHint("The microphone isn't available. Type your request instead.")
            }
        }
    }

    fun onMicRelease() {
        if (_state.value.session.phase != Phase.LISTENING) return
        setPhase(Phase.THINKING)
        runTurn {
            val transcript = try {
                container.stt.stopAndTranscribe().trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            if (transcript.isEmpty()) {
                assistantSays("I didn't catch that. Try again, or type your request.", outcome = null)
            } else {
                handleUtterance(transcript)
            }
        }
    }

    private suspend fun handleUtterance(text: String) {
        addTurn(fromUser = true, text = text)
        setPhase(Phase.THINKING)

        var command: TaskCommand? = null
        val result = try {
            withContext(Dispatchers.IO) {
                val openTitles = engine.tasks().filter { !it.isDone }.map { it.title }
                val parsed = container.parser.parse(text, ParseContext(openTitles))
                command = parsed
                engine.execute(parsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineResult(Outcome.REJECTED, "Sorry, something went wrong. Please try again.")
        }

        safely { reloadTasksAsync() }
        command?.let { afterCommand(it, result) }
        setQuickActions(quickActionsFor(command, result))
        result.plan?.let { showPlan(it, returnTo = Screen.SESSION) }
        assistantSays(result.spoken, result.outcome)
        if (result.outcome == Outcome.END_CALL) endToHome()
    }

    private suspend fun assistantSays(text: String, outcome: Outcome?) {
        addTurn(fromUser = false, text = text, outcome = outcome)
        setPhase(Phase.SPEAKING)
        try {
            container.speaker.speak(text)
        } finally {
            setPhase(Phase.IDLE)
        }
    }

    private fun acceptingInput(): Boolean {
        val phase = _state.value.session.phase
        return phase == Phase.IDLE || phase == Phase.SPEAKING
    }

    private fun interruptSpeech() {
        if (_state.value.session.phase == Phase.SPEAKING) {
            container.speaker.stop()
            turnJob?.cancel()
        }
    }

    private fun runTurn(block: suspend () -> Unit) {
        turnJob?.cancel()
        turnJob = viewModelScope.launch { block() }
    }

    private fun addTurn(fromUser: Boolean, text: String, outcome: Outcome? = null) {
        val turn = Turn(nextTurnId++, fromUser, text, outcome)
        _state.update { it.copy(session = it.session.copy(turns = it.session.turns + turn, hint = null)) }
    }

    private fun setPhase(phase: Phase) =
        _state.update { it.copy(session = it.session.copy(phase = phase)) }

    private fun setHint(hint: String) =
        _state.update { it.copy(session = it.session.copy(hint = hint)) }

    private fun showPlan(plan: Plan, returnTo: Screen) =
        _state.update { it.copy(plan = plan, planReturnsTo = returnTo, screen = Screen.PLAN) }

    // ---- plumbing ------------------------------------------------------------------------

    private suspend fun run(command: TaskCommand): EngineResult {
        val result = try {
            withContext(Dispatchers.IO) { engine.execute(command) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineResult(Outcome.REJECTED, "Sorry, something went wrong. Please try again.")
        }
        safely { reloadTasksAsync() }
        afterCommand(command, result)
        return result
    }

    /** Tracks whether an undo is available and offers it (once) after every successful change. */
    private fun afterCommand(command: TaskCommand, result: EngineResult) {
        if (!result.isSuccess) return
        when (command) {
            TaskCommand.Undo -> _state.update { it.copy(canUndo = false) }
            is TaskCommand.AddTask, is TaskCommand.UpdateTask, is TaskCommand.CompleteTask,
            is TaskCommand.ReopenTask, is TaskCommand.DeleteTask, is TaskCommand.Snooze -> {
                _state.update { it.copy(canUndo = true) }
                _events.tryEmit(UiEvent.ShowUndoSnackbar(result.spoken))
            }
            else -> Unit
        }
    }

    /** "+1 Hour", "Tomorrow 9 AM" and "Snooze 15m" for the single task the last reply was about. */
    private fun quickActionsFor(command: TaskCommand?, result: EngineResult): List<QuickAction> {
        if (!result.isSuccess || command == null || command is TaskCommand.Undo || command is TaskCommand.QueryTasks) {
            return emptyList()
        }
        val task = result.taskIds.singleOrNull()?.let { id -> _state.value.tasks.firstOrNull { it.id == id } }
        val deadline = task?.deadline
        if (task == null || task.isDone || deadline == null) return emptyList()

        val zone: ZoneId = clock.zone
        val today = clock.instant().atZone(zone).toLocalDate()
        val actions = mutableListOf<QuickAction>()

        val plusHour = deadline + 3_600_000L
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, java.time.Instant.ofEpochMilli(plusHour).atZone(zone).toLocalDate())
        if (plusHour > clock.millis() && days in 0..6) {
            val day = format.day(plusHour)
            actions += QuickAction("+1 Hour", "change the deadline to $day at ${format.clockTime(plusHour).lowercase()}")
        }
        actions += QuickAction("Tomorrow 9 AM", "change the deadline to tomorrow at 9 am")
        actions += QuickAction("Snooze 15m", "snooze 15 minutes")
        return actions
    }

    private fun setQuickActions(actions: List<QuickAction>) =
        _state.update { it.copy(session = it.session.copy(quickActions = actions)) }

    private suspend fun reloadTasksAsync() = withContext(Dispatchers.IO) { reloadTasks() }

    private fun reloadTasks() {
        val tasks = container.store.all()
        _state.update { it.copy(tasks = tasks, loaded = true) }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loaded = true) }
        }
    }

    override fun onCleared() {
        ringer.stop()
        super.onCleared()
    }
}

private const val RING_TIMEOUT_MS = 30_000L
private const val SNOOZE_MINUTES = 15
