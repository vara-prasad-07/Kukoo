package com.example.kukoo.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kukoo.KukooApp
import com.example.kukoo.ai.InstallProgress
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.ai.SpeechModel
import com.geniex.sdk.ModelManagerWrapper
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
import kotlinx.coroutines.flow.onCompletion
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

    /** Per-turn diagnostics are for debug builds only: what was heard and how it was understood. */
    private val debuggable = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
            // A download keeps running in the background; leaving the screen does not cancel it.
            Screen.SETUP -> _state.update { it.copy(screen = Screen.HOME) }
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

    // ---- on-device model setup -----------------------------------------------------------

    fun openModelSetup() {
        _state.update { it.copy(screen = Screen.SETUP) }
        refreshSetup()
    }

    fun closeModelSetup() = _state.update { it.copy(screen = Screen.HOME) }

    /** Recomputes what is installed. Called on entry and after every download finishes. */
    fun refreshSetup() {
        viewModelScope.launch(Dispatchers.IO) {
            val speech = SpeechModel.entries.map { model ->
                val installed = container.models.isInstalled(model)
                ModelRow(
                    id = model.name,
                    label = model.label,
                    detail = if (installed) "Installed" else "${model.bytes / 1_000_000} MB" +
                        if (model.required) "" else " · optional",
                    installed = installed,
                )
            }
            val llmReady = container.genieX.isModelDownloaded
            val chipset = runCatching { container.genieX.detectChipset() }.getOrNull().orEmpty()
            val llm = ModelRow(
                id = "LLM",
                label = "Qwen3-VL-4B-Instruct (NPU)",
                detail = if (llmReady) "Installed" else "Downloads from Qualcomm AI Hub",
                installed = llmReady,
            )
            _state.update { it.copy(setup = it.setup.copy(speech = speech, llm = llm, chipset = chipset)) }
        }
    }

    fun onMicPermissionResult(granted: Boolean) =
        _state.update { it.copy(setup = it.setup.copy(micGranted = granted)) }

    /** Downloads the sherpa-onnx speech bundles, updating one row at a time. */
    fun downloadSpeechModels() {
        if (_state.value.setup.busy) return
        _state.update { it.copy(setup = it.setup.copy(busy = true)) }
        viewModelScope.launch {
            container.models.install()
                .onCompletion { _state.update { s -> s.copy(setup = s.setup.copy(busy = false)) }; refreshSetup() }
                .collect { progress -> updateSpeechRow(progress) }
        }
    }

    private fun updateSpeechRow(progress: InstallProgress) = _state.update { state ->
        val rows = state.setup.speech.map { row ->
            if (row.id != progress.model.name) row else row.copy(
                fraction = progress.fraction,
                installed = progress.done,
                downloading = !progress.done && progress.error == null,
                error = progress.error,
                detail = when {
                    progress.error != null -> "Failed: ${progress.error}"
                    progress.done -> "Installed"
                    else -> "${progress.downloaded / 1_000_000} / ${progress.total / 1_000_000} MB"
                },
            )
        }
        state.copy(setup = state.setup.copy(speech = rows))
    }

    /**
     * Pulls the NPU model bundle, then loads it so the first call does not pay the cold-load cost.
     * GenieX reports progress per file, so the bar is the sum over all files in flight.
     */
    fun downloadLlm() {
        if (_state.value.setup.busy) return
        _state.update { it.copy(setup = it.setup.copy(busy = true)) }
        viewModelScope.launch {
            try {
                // The bundle is ~1.5 GB. A single dropped connection on venue wifi would otherwise
                // throw away the whole download, so keep retrying: GenieX leaves the partial files
                // in place and resumes from where it stopped.
                for (attempt in 1..PULL_ATTEMPTS) {
                    val failure = attemptPull(attempt)
                    if (failure == null) return@launch
                    if (attempt == PULL_ATTEMPTS) {
                        setLlmRow(detail = "Failed after $attempt tries: $failure", error = failure)
                    } else {
                        setLlmRow(detail = "Connection lost — resuming (try ${attempt + 1})…", downloading = true)
                        delay(RETRY_DELAY_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(setup = it.setup.copy(busy = false)) }
            }
        }
    }

    /** One pull attempt. Returns null on success, or the failure message to retry on. */
    private suspend fun attemptPull(attempt: Int): String? {
        setLlmRow(detail = if (attempt == 1) "Contacting AI Hub…" else "Resuming…", downloading = true)
        var failure: String? = null
        var completed = false
        try {
            container.genieX.pullModel().collect { event ->
                when (event) {
                    is ModelManagerWrapper.PullEvent.Progress -> {
                        val got = event.files.sumOf { it.downloaded_bytes }
                        val total = event.files.sumOf { it.total_bytes.coerceAtLeast(0) }
                        setLlmRow(
                            detail = if (total > 0) "${got / 1_000_000} / ${total / 1_000_000} MB"
                            else "${got / 1_000_000} MB",
                            fraction = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else 0f,
                            downloading = true,
                        )
                    }
                    ModelManagerWrapper.PullEvent.Completed -> completed = true
                    is ModelManagerWrapper.PullEvent.Error -> failure = event.message
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        }
        if (!completed) return failure ?: "Download did not finish"

        setLlmRow(detail = "Loading onto the NPU…", fraction = 1f, downloading = true)
        val ok = withContext(Dispatchers.IO) { container.genieX.ensureLoaded() }
        setLlmRow(
            detail = if (ok) "Installed" else "Downloaded, but the NPU load failed",
            fraction = 1f,
            installed = ok,
        )
        return null
    }

    private fun setLlmRow(
        detail: String,
        fraction: Float = 0f,
        installed: Boolean = false,
        downloading: Boolean = false,
        error: String? = null,
    ) = _state.update { state ->
        val row = (state.setup.llm ?: ModelRow("LLM", "Qwen3-VL-4B-Instruct (NPU)", detail))
            .copy(detail = detail, fraction = fraction, installed = installed, downloading = downloading, error = error)
        state.copy(setup = state.setup.copy(llm = row))
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
                // The pending draft tells the parser a short reply ("an hour", "high") answers the
                // question just asked. Spoken adds are asked for name, deadline, duration and priority.
                val parsed = container.parser.parse(text, ParseContext(openTitles, engine.pendingDraft))
                command = parsed
                engine.executeSpoken(parsed).also { result ->
                    if (debuggable) Log.i(TAG, "heard=\"$text\" parsed=$parsed -> ${result.outcome}: ${result.spoken}")
                }
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
            is TaskCommand.AddTask, is TaskCommand.StartTask, is TaskCommand.FillTask,
            is TaskCommand.UpdateTask, is TaskCommand.CompleteTask,
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

private const val TAG = "KukooTurn"
private const val RING_TIMEOUT_MS = 30_000L
private const val SNOOZE_MINUTES = 15

/** A 1.5 GB pull over conference wifi drops often; each retry resumes from the partial files. */
private const val PULL_ATTEMPTS = 40
private const val RETRY_DELAY_MS = 3_000L
