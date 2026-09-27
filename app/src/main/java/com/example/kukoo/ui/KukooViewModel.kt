package com.example.kukoo.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kukoo.KukooApp
import com.example.kukoo.ai.InstallProgress
import com.example.kukoo.ai.ConversationTurn
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.ai.SpeechModel
import com.geniex.sdk.ModelManagerWrapper
import com.example.kukoo.call.CallAlarmReceiver
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /** Task reminders that came due while another call was ringing or in progress, oldest first. */
    private val queuedReminders = ArrayDeque<Long>()

    val format: TimeFormat get() = engine.format
    val clock: Clock get() = container.clock

    private val _state = MutableStateFlow(AppState(nextCallAt = container.scheduler.nextDailyAt()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var turnJob: Job? = null
    private var nextTurnId = 1L

    /** Hold-to-talk unless the user turns hands-free on; kept for the life of the app. */
    private var handsFree = false

    /** The mic only works while the app is visible; see [onAppBackgrounded]. */
    private var inForeground = true

    /** Per-turn diagnostics are for debug builds only: what was heard and how it was understood. */
    private val debuggable = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.seedOnFirstRun() }
            safely { reloadTasks() }
        }
        viewModelScope.launch {
            container.incomingCalls.collect { onIncomingCall(fromAlarm = true, taskId = it) }
        }
    }

    // ---- navigation ----------------------------------------------------------------------

    /** Returns true when the back press was handled (false on Home: let the system leave the app). */
    fun onBack(): Boolean {
        when (_state.value.screen) {
            Screen.HOME -> return false
            Screen.INCOMING_CALL, Screen.SESSION -> endToHome()
            Screen.PLAN -> {
                val returnsTo = _state.value.planReturnsTo
                _state.update { it.copy(screen = returnsTo) }
                // The call kept its place; the assistant listens again once the user is back in it.
                if (returnsTo == Screen.SESSION && _state.value.session.phase == Phase.IDLE) runTurn { }
            }
            // A download keeps running in the background; leaving the screen does not cancel it.
            Screen.SETUP -> _state.update { it.copy(screen = Screen.HOME) }
        }
        return true
    }

    fun onAppBackgrounded() {
        // Leaving the app while it rings counts as a missed call. Over the lock screen the keyguard can
        // stop the activity as the call appears, so that must not drop it; the ring timeout still ends it.
        inForeground = false
        val s = _state.value
        if (s.screen == Screen.INCOMING_CALL && !s.overLockscreen) endToHome()
        // Android gives a background app silence from the mic, so stop listening rather than pretend to.
        if (s.screen == Screen.SESSION && s.session.phase == Phase.LISTENING) pauseListening("Paused while the app was in the background.")
    }

    /** Back on screen: pick the conversation up again if it was paused only because the app left. */
    fun onAppForegrounded() {
        inForeground = true
        val s = _state.value
        if (s.screen == Screen.SESSION && s.session.phase == Phase.IDLE) runTurn { }
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
        recurrence: Recurrence = Recurrence.NONE,
        reminderMin: Int? = null,
        keepOverlaps: Boolean = false
    ) {
        val editing = _state.value.editor?.task
        val spec = deadline?.let { DeadlineSpec.Exact(it) }
        val command = if (editing == null) {
            TaskCommand.AddTask(title, spec, durationMin, priority, recurrence, notes, reminderMin, keepOverlaps)
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
                    notes = notes ?: "",
                    reminderMin = reminderMin,
                    clearReminder = reminderMin == null,
                    keepOverlaps = keepOverlaps
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
            // Downloaded is not the same as running: the NPU load needs several GB and fails while
            // another app holds them. Saying only "Installed" hid that the rule parser was doing
            // every turn, so the state the user actually cares about is shown instead.
            val llm = ModelRow(
                id = "LLM",
                label = "Qwen3-VL-4B-Instruct (NPU)",
                detail = when {
                    container.genieX.isLoaded -> "Installed and running on the NPU"
                    llmReady -> "Installed — waiting for the NPU to free up. Restarting the phone frees it."
                    else -> "Downloads from Qualcomm AI Hub"
                },
                installed = llmReady,
            )
            _state.update { it.copy(setup = it.setup.copy(speech = speech, llm = llm, chipset = chipset)) }
        }
    }

    /** Downloads the sherpa-onnx speech bundles, updating one row at a time. */
    fun downloadSpeechModels(only: SpeechModel? = null) {
        if (_state.value.setup.busy) return
        _state.update { it.copy(setup = it.setup.copy(busy = true)) }
        viewModelScope.launch {
            container.models.install(if (only != null) listOf(only) else SpeechModel.entries)
                .onCompletion { _state.update { s -> s.copy(setup = s.setup.copy(busy = false)) }; refreshSetup() }
                .collect { progress -> updateSpeechRow(progress) }
        }
    }

    /** Downloads the one model whose row was tapped. */
    fun downloadSpeechModel(id: String) {
        SpeechModel.entries.firstOrNull { it.name == id }?.let { downloadSpeechModels(it) }
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
    fun onIncomingCall(fromAlarm: Boolean, taskId: Long = CallAlarmReceiver.NO_TASK) {
        val current = _state.value.screen
        val forTask = taskId.takeIf { it != CallAlarmReceiver.NO_TASK }
        if (current == Screen.SESSION || current == Screen.INCOMING_CALL) {
            // A reminder that comes due mid-call is not lost: it rings as soon as this call is over.
            if (forTask != null && forTask != _state.value.callTaskId && forTask !in queuedReminders) {
                queuedReminders.addLast(forTask)
            }
            return
        }
        _state.update {
            it.copy(screen = Screen.INCOMING_CALL, overLockscreen = fromAlarm, editor = null, callTaskId = forTask)
        }
        ringer.start()
        ringTimeout?.cancel()
        ringTimeout = viewModelScope.launch {
            delay(RING_TIMEOUT_MS)
            if (_state.value.screen == Screen.INCOMING_CALL) endToHome()
        }
    }

    /** Declined or timed out (30s): silence the ringer and go back Home. */
    fun declineCall() = endToHome()

    /**
     * "Snooze" on the ringing call. A task reminder just rings again in [REMINDER_SNOOZE_MINUTES] and leaves the
     * deadline alone; the daily call pushes today's tasks back 15 minutes.
     */
    fun snoozeCall() {
        ringTimeout?.cancel()
        ringer.stop()
        val taskId = _state.value.callTaskId
        if (taskId != null) {
            container.scheduler.snoozeReminder(taskId, REMINDER_SNOOZE_MINUTES)
            endToHome()
            return
        }
        viewModelScope.launch {
            safely { run(TaskCommand.Snooze(SNOOZE_MINUTES)) }
            endToHome()
        }
    }

    /** Silence the ringer (and release audio focus) before the session starts speaking/listening. */
    fun answerCall() {
        ringer.stop()
        beginSession(_state.value.callTaskId)
    }

    /** The user opened the assistant themselves (Talk button): same session, no ringing. */
    fun startSession() = beginSession(null)

    // ---- voice session -------------------------------------------------------------------

    /** [taskId] set: a reminder call, briefed on that one task only. */
    private fun beginSession(taskId: Long?) {
        _state.update { it.copy(canUndo = false) }
        ringTimeout?.cancel()
        ringer.stop()
        turnJob?.cancel()
        engine.resetContext()
        _state.update {
            it.copy(
                screen = Screen.SESSION,
                session = SessionState(startedAt = container.clock.millis(), micReady = container.stt.isReady, handsFree = handsFree),
                plan = null,
                editor = null,
                callTaskId = taskId
            )
        }
        runTurn {
            val briefing = withContext(Dispatchers.IO) {
                taskId?.let { engine.taskBriefing(it) } ?: engine.briefing()
            }
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
            it.copy(screen = Screen.HOME, session = SessionState(), plan = null, overLockscreen = false, callTaskId = null)
        }
        viewModelScope.launch { safely { reloadTasksAsync() } }
        ringNextQueuedReminder()
    }

    /** Rings the oldest reminder that came due during the call that just ended, skipping tasks finished since. */
    private fun ringNextQueuedReminder() {
        val id = queuedReminders.removeFirstOrNull() ?: return
        viewModelScope.launch {
            val stillOpen = withContext(Dispatchers.IO) { runCatching { container.store.get(id) }.getOrNull() }
                ?.let { !it.isDone } == true
            if (stillOpen) onIncomingCall(fromAlarm = false, taskId = id) else ringNextQueuedReminder()
        }
    }

    /** Typed text and tapped suggestions take the same path as a spoken transcript. */
    fun submitText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || !acceptingInput()) return
        interruptSpeech()
        runTurn { handleUtterance(text) }
    }

    /** Switches between hold-to-talk and the assistant listening by itself after each reply. */
    fun setHandsFree(on: Boolean) {
        handsFree = on
        _state.update { it.copy(session = it.session.copy(handsFree = on, hint = null)) }
        val phase = _state.value.session.phase
        if (on) {
            if (phase == Phase.IDLE) runTurn { }
        } else if (phase == Phase.LISTENING) {
            pauseListening(null)
        }
    }

    /** Hold-to-talk: the button went down. Interrupts the assistant and records until release. */
    fun onMicPress() {
        if (!container.stt.isReady) {
            setHint("Voice input isn't ready. Allow the microphone and install the speech model, or type below.")
            return
        }
        val phase = _state.value.session.phase
        if (phase == Phase.THINKING) return
        if (phase == Phase.SPEAKING) container.speaker.stop()
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            setPhase(Phase.LISTENING)
            _state.update { it.copy(session = it.session.copy(hint = null)) }
            val transcript = try {
                container.stt.listen(manual = true, onCaptured = { setPhase(Phase.THINKING) }).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listening failed", e)
                setPhase(Phase.IDLE)
                setHint("The microphone isn't available. Type your request instead.")
                return@launch
            }
            if (transcript.isEmpty()) {
                assistantSays("I didn't catch that. Hold the mic, speak, then let go.", outcome = null)
            } else {
                handleUtterance(transcript)
            }
            listenLoop()
        }
    }

    /** Hold-to-talk: the button was released; what was recorded is transcribed and sent. */
    fun onMicRelease() {
        if (_state.value.session.phase == Phase.LISTENING) container.stt.finishUtterance()
    }

    /** Hands-free mode: tap to send what has been said so far, or to pause/resume listening. */
    fun onMicTap() {
        if (!container.stt.isReady) {
            setHint("Voice input isn't ready. Allow the microphone and install the speech model, or type below.")
            return
        }
        when (_state.value.session.phase) {
            // Mid-sentence a tap means "I'm done, send it"; before the user has said anything it pauses.
            Phase.LISTENING -> if (!container.stt.finishUtterance()) pauseListening("Mic paused. Tap the mic to talk.")
            Phase.SPEAKING -> {
                interruptSpeech()
                runTurn { }
            }
            Phase.IDLE -> runTurn { }
            Phase.THINKING -> Unit
        }
    }

    /** Called when the microphone permission dialog is answered mid-call, so listening can begin. */
    fun onMicPermissionResult(granted: Boolean) {
        _state.update { it.copy(setup = it.setup.copy(micGranted = granted)) }
        val s = _state.value
        if (granted && s.screen == Screen.SESSION) {
            _state.update { it.copy(session = it.session.copy(micReady = container.stt.isReady, hint = null)) }
            if (s.session.phase == Phase.IDLE) runTurn { }
        }
    }

    private fun pauseListening(hint: String?) {
        turnJob?.cancel()
        setPhase(Phase.IDLE)
        _state.update { it.copy(session = it.session.copy(hint = hint)) }
    }

    /**
     * Hands-free turn-taking: with the assistant done talking, open the microphone, wait for the user
     * to speak and pause, answer, and go round again. Stops when the call ends, the screen changes,
     * or nothing has been said for a while (the mic button then resumes it).
     */
    private suspend fun listenLoop() {
        var silentListens = 0
        fun canListen() = handsFree && _state.value.screen == Screen.SESSION && inForeground && container.stt.isReady
        while (currentCoroutineContext().isActive && canListen()) {
            // Let the tail of the assistant's voice die away so it is not heard as the user.
            delay(LISTEN_GAP_MS)
            if (!canListen()) return
            setPhase(Phase.LISTENING)
            _state.update { it.copy(session = it.session.copy(hint = null)) }
            var captured = false
            val transcript = try {
                container.stt.listen(onCaptured = { captured = true; setPhase(Phase.THINKING) }).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listening failed", e)
                setPhase(Phase.IDLE)
                setHint("The microphone isn't available. Type your request instead.")
                return
            }
            if (transcript.isNotEmpty()) {
                silentListens = 0
                handleUtterance(transcript)
            } else {
                // Heard something but could not read it: say so once. Silence is not nagged about.
                if (captured) assistantSays("I didn't catch that. Please say it again.", outcome = null)
                if (++silentListens >= MAX_SILENT_LISTENS) {
                    setPhase(Phase.IDLE)
                    setHint("I stopped listening. Tap the mic when you want to talk.")
                    return
                }
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
                val recent = _state.value.session.turns
                    .takeLast(ParseContext.HISTORY_TURNS)
                    .map { ConversationTurn(it.fromUser, it.text) }
                val parsed = container.parser.parse(
                    text,
                    ParseContext(openTitles, engine.pendingDraft, recent, engine.pendingConflict),
                )
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
            // A cancelled turn was replaced (or ended) by whoever cancelled it, which owns the
            // phase now; resetting it here could overwrite the new turn's LISTENING.
            if (currentCoroutineContext().isActive) setPhase(Phase.IDLE)
        }
    }

    /** Typing is allowed any time the assistant is not busy working out an answer, including while it listens. */
    private fun acceptingInput(): Boolean {
        val phase = _state.value.session.phase
        return phase == Phase.IDLE || phase == Phase.SPEAKING || phase == Phase.LISTENING
    }

    private fun interruptSpeech() {
        if (_state.value.session.phase == Phase.SPEAKING) {
            container.speaker.stop()
            turnJob?.cancel()
        }
    }

    /** Runs one turn, then (in hands-free mode) hands the floor back to the user by listening again. */
    private fun runTurn(block: suspend () -> Unit) {
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            block()
            listenLoop()
        }
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
            actions += QuickAction("+1 Hour", "change the time to $day at ${format.clockTime(plusHour).lowercase()}")
        }
        actions += QuickAction("Tomorrow 9 AM", "change the time to tomorrow at 9 am")
        actions += QuickAction("Snooze 15m", "snooze 15 minutes")
        return actions
    }

    private fun setQuickActions(actions: List<QuickAction>) =
        _state.update { it.copy(session = it.session.copy(quickActions = actions)) }

    private suspend fun reloadTasksAsync() = withContext(Dispatchers.IO) { reloadTasks() }

    private fun reloadTasks() {
        val tasks = container.store.all()
        // Every add, edit, completion, delete and undo lands here, so the reminder alarms follow along.
        runCatching { container.scheduler.syncReminders(tasks) }
            .onFailure { Log.w(TAG, "Could not schedule task reminders", it) }
        _state.update { it.copy(tasks = tasks, acks = container.store.acks(), loaded = true) }
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
private const val REMINDER_SNOOZE_MINUTES = 5
private const val LISTEN_GAP_MS = 400L

/** Listens this many times in a row (about 10 s each) with no speech before pausing. */
private const val MAX_SILENT_LISTENS = 3

/** A 1.5 GB pull over conference wifi drops often; each retry resumes from the partial files. */
private const val PULL_ATTEMPTS = 40
private const val RETRY_DELAY_MS = 3_000L
