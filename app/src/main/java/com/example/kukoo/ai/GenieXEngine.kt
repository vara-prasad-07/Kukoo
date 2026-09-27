package com.example.kukoo.ai

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Qwen3-VL-4B-Instruct on the Hexagon NPU through Qualcomm GenieX.
 *
 * Implements [LlamaEngine], so [LlamaIntentParser] keeps all of its existing behaviour — the 8 s
 * timeout, the single-inference mutex, JSON validation and the fall-through to
 * [RuleBasedIntentParser] when anything goes wrong. Only the text completion changes.
 *
 * The model is a VLM rather than a plain LLM because the same weights also back the
 * photo-to-tasks capture flow ([generate] accepts image paths), so only one bundle is downloaded
 * and only one model is resident.
 */
class GenieXEngine(context: Context) : LlamaEngine {
    private val appContext = context.applicationContext
    private val debuggable = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Volatile
    private var vlm: VlmWrapper? = null

    @Volatile
    private var sdkReady = false

    /** Set once a load has failed, so every turn does not retry a 30-second model load. */
    @Volatile
    private var loadFailed = false

    private val loadLock = Any()
    private val generateLock = Mutex()

    /** True when the model bundle is on disk, so the UI can offer setup before a call starts. */
    val isModelDownloaded: Boolean
        get() = runCatching { runBlocking { resolvedPaths() != null } }.getOrDefault(false)

    // ---- setup ---------------------------------------------------------------------------

    /**
     * Initialises the native SDK: loads `libnpu_jni.so`, registers the qairt and llama_cpp
     * plugins, and points the model manager at `filesDir/geniex`. Safe to call repeatedly.
     */
    suspend fun initSdk(): Result<Unit> {
        if (sdkReady) return Result.success(Unit)
        val failure = StringBuilder()
        GenieXSdk.getInstance().init(appContext, object : GenieXSdk.InitCallback {
            override fun onSuccess() = Unit
            override fun onFailure(reason: String) {
                failure.append(reason)
            }
        })
        if (failure.isNotEmpty()) {
            Log.e(TAG, "GenieX init failed: $failure")
            return Result.failure(IllegalStateException(failure.toString()))
        }
        val dataDir = File(appContext.filesDir, "geniex").apply { mkdirs() }
        return ModelManagerWrapper.init(dataDir.absolutePath).onSuccess {
            sdkReady = true
            Log.i(TAG, "GenieX ready, model cache at ${dataDir.absolutePath}")
        }
    }

    /**
     * Downloads the model bundle for this chipset. Emits GenieX's own pull events so the caller
     * can show per-file progress; terminates with `Completed` or `Error`.
     */
    suspend fun pullModel(): Flow<ModelManagerWrapper.PullEvent> {
        val target = resolveTarget()
            ?: throw IllegalStateException("No AI Hub bundle for this chipset (${detectChipset()})")
        resolvedName = target.modelName
        Log.i(TAG, "pulling ${target.modelName} for chipset ${target.chipset}")
        return ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = target.modelName,
                // AUTO routes a "qualcomm/…" name to AI Hub on its own.
                // display_name is deliberately left null: despite the field docs it is the AI Hub
                // *model* display name, not the device, and setting it to the chipset label makes
                // the pull look for a model called "Samsung Galaxy S26" and fail with -100010.
                hub = HubSource.AUTO,
                chipset = target.chipset,
            ),
        )
    }

    /**
     * The model name and hub chipset alias actually usable on this device. [displayName] is the
     * hub's device label (e.g. "Samsung Galaxy S26"), which AI Hub pulls want alongside the alias.
     */
    data class Target(val modelName: String, val chipset: String, val displayName: String?)

    /**
     * The raw SoC id, e.g. "SM8850". Useful for display, but **not** accepted by the hub: pulls and
     * [ModelManagerWrapper.listHubModels] want one of the aliases from
     * [ModelManagerWrapper.listChipsets], such as "sm8850-ad". Passing the SoC id straight through
     * is what produced `chipset "SM8850" not found in platform.json` and a -100015 pull failure.
     */
    suspend fun detectChipset(): String =
        runCatching { ModelManagerWrapper.detectChipset(offline = true) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SOC

    /**
     * Resolves what to pull entirely from the hub's own catalog rather than from hard-coded
     * strings: finds the chipset entry whose aliases match this SoC, then picks the first model in
     * [PREFERRED_MODELS] that publishes a bundle for one of those aliases.
     *
     * Returns null when nothing in the catalog fits, so the caller can say so instead of failing
     * deep inside the native pull.
     */
    suspend fun resolveTarget(): Target? {
        initSdk().getOrThrow()
        val soc = detectChipset()

        val info = runCatching { ModelManagerWrapper.listChipsets() }.getOrNull().orEmpty()
            .firstOrNull { entry ->
                entry.aliases.any { it.equals(soc, true) || it.startsWith(soc, true) }
            }
        val aliases = info?.aliases?.toList().orEmpty()
        if (aliases.isEmpty()) {
            Log.w(TAG, "no hub chipset matches SoC $soc")
            return null
        }
        Log.i(TAG, "SoC $soc maps to ${info?.name} $aliases")

        val models = runCatching { ModelManagerWrapper.listHubModels(null) }.getOrNull().orEmpty()
        // An empty catalog means the hub was unreachable, not that this device is unsupported.
        // Saying "no bundle for your chipset" here sent us chasing the wrong bug once already.
        if (models.isEmpty()) throw IOException("Could not reach AI Hub to list models")
        for (wanted in PREFERRED_MODELS) {
            val model = models.firstOrNull { it.name.equals(wanted, true) } ?: continue
            val chipset = model.chipsets.firstOrNull { c -> aliases.any { it.equals(c, true) } } ?: continue
            Log.i(TAG, "resolved target: ${model.name} on $chipset (${info?.name})")
            return Target(model.name, chipset, info?.name)
        }
        Log.w(TAG, "none of $PREFERRED_MODELS available for $soc; catalog has ${models.size} models")
        return null
    }

    /**
     * Dumps what this SDK build can actually run here: every chipset it knows and every AI Hub
     * bundle available for this device. `geniex_model_pull` returning -100015
     * (CHIPSET_UNAVAILABLE) means the requested model has no bundle for our chipset, and this is
     * the only way to see which ones do.
     */
    suspend fun logCatalog() {
        runCatching { initSdk() }
        val detected = runCatching { ModelManagerWrapper.detectChipset(offline = true) }.getOrNull()
        Log.i(TAG, "CATALOG detectChipset(offline=true)=$detected, fallback=$DEFAULT_SOC")
        runCatching { ModelManagerWrapper.detectChipset(offline = false) }
            .onSuccess { Log.i(TAG, "CATALOG detectChipset(offline=false)=$it") }
            .onFailure { Log.w(TAG, "CATALOG detectChipset(online) failed", it) }

        runCatching { ModelManagerWrapper.listChipsets() }
            .onSuccess { list ->
                Log.i(TAG, "CATALOG known chipsets (${list.size}): $list")
            }
            .onFailure { Log.w(TAG, "CATALOG listChipsets failed", it) }

        val chipset = detected?.takeIf { it.isNotBlank() } ?: DEFAULT_SOC
        runCatching { ModelManagerWrapper.listHubModels(chipset) }
            .onSuccess { models ->
                Log.i(TAG, "CATALOG models for $chipset (${models.size}):")
                models.forEach { Log.i(TAG, "CATALOG   ${it.model_type} ${it.name} -> ${it.chipsets.joinToString()}") }
            }
            .onFailure { Log.w(TAG, "CATALOG listHubModels($chipset) failed", it) }

        // Unfiltered, so a model that exists but is bound to a differently-spelled chipset shows up.
        runCatching { ModelManagerWrapper.listHubModels(null) }
            .onSuccess { models ->
                Log.i(TAG, "CATALOG all hub models (${models.size}):")
                models.forEach { Log.i(TAG, "CATALOG * ${it.model_type} ${it.name} -> ${it.chipsets.joinToString()}") }
            }
            .onFailure { Log.w(TAG, "CATALOG listHubModels(all) failed", it) }

        runCatching { resolveTarget() }
            .onSuccess { Log.i(TAG, "CATALOG resolved target = $it") }
            .onFailure { Log.w(TAG, "CATALOG resolveTarget failed", it) }
    }

    /** Set once a pull resolves, so later lookups use the name the hub actually published. */
    @Volatile
    private var resolvedName: String? = null

    /**
     * Locates the downloaded bundle. Tries the name a pull resolved to, then the preferred names,
     * then anything in the local cache that looks like one of them — so a model sideloaded with a
     * slightly different name is still found.
     */
    private suspend fun resolvedPaths() = runCatching {
        val cached = ModelManagerWrapper.list()
        val candidates = listOfNotNull(resolvedName) + PREFERRED_MODELS
        val name = candidates.firstOrNull { c -> cached.any { it.equals(c, true) } }
            ?: cached.firstOrNull { c -> PREFERRED_MODELS.any { c.contains(it.substringAfter('/'), true) } }
            ?: return@runCatching null
        ModelManagerWrapper.getPaths(name)
    }.getOrNull()

    // ---- LlamaEngine --------------------------------------------------------------------

    override fun ensureLoaded(): Boolean {
        vlm?.let { return true }
        if (loadFailed) return false
        // LlamaIntentParser already calls this from Dispatchers.Default, so blocking here keeps the
        // existing synchronous LlamaEngine contract without adding a second parser implementation.
        synchronized(loadLock) {
            vlm?.let { return true }
            if (loadFailed) return false
            return runCatching { runBlocking { load() } }
                .onFailure { Log.w(TAG, "VLM load failed", it) }
                .getOrDefault(false)
                .also { if (!it) loadFailed = true }
        }
    }

    /** True once the model is resident on the NPU and turns can use it. */
    val isLoaded: Boolean get() = vlm != null

    /**
     * Forgets an earlier load failure and tries again. A load can fail while the NPU is still being
     * released by a previous process, so [AppContainer.warmUp] calls this a few times after startup
     * instead of leaving the model switched off for the rest of the session.
     */
    fun retryLoad(): Boolean {
        synchronized(loadLock) {
            if (vlm != null) return true
            loadFailed = false
        }
        return ensureLoaded()
    }

    private suspend fun load(): Boolean {
        initSdk().getOrThrow()
        val paths = resolvedPaths() ?: run {
            Log.w(TAG, "model not downloaded yet")
            return false
        }
        Log.i(TAG, "loading ${paths.model_name} runtime=${paths.runtime_id} mmproj=${paths.mmproj_path}")
        val wrapper = VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_path = paths.model_path,
                    mmproj_path = paths.mmproj_path,
                    // qairt rejects a non-zero n_ctx / n_gpu_layers: the context length is baked
                    // into the pre-compiled bundle, so passing ModelConfig() defaults fails here.
                    config = ModelConfig(nCtx = 0, nGpuLayers = 0, nThreads = 8),
                    runtime_id = RuntimeIdValue.QAIRT.value,
                    // null selects the runtime default, which is NPU for qairt.
                    compute_unit = COMPUTE_UNIT,
                ),
            )
            .build()
            .getOrThrow()
        vlm = wrapper
        return true
    }

    override fun complete(prompt: String, maxTokens: Int, grammar: String?): String =
        runBlocking { generate(prompt, maxTokens, grammar) }

    /**
     * Runs one generation.
     *
     * [imagePaths] are absolute files on disk; when present they are attached to the user turn so
     * GenieX can hand them to the vision tower.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        grammar: String? = null,
        imagePaths: List<String> = emptyList(),
    ): String = generateLock.withLock {
        val wrapper = vlm ?: throw IllegalStateException("Model not loaded")
        // Each parse is an independent classification, not a continuation of the last one: the
        // conversation the model should see is built into the prompt. Without this the dialog's
        // KV cache carried the previous turns over and the model answered a stale question —
        // "thank you" came back as the query from two turns earlier, then repeated itself.
        runCatching { wrapper.reset() }.onFailure { Log.w(TAG, "reset failed", it) }
        // The parser hands us a ChatML string. Re-splitting it into messages and letting the model
        // apply its own template is what makes <|im_start|> land as real special tokens: a raw
        // prompt string would be tokenized as literal text and quality would quietly drop.
        val messages = asChatMessages(prompt, imagePaths)
        val formatted = messages?.let {
            wrapper.applyChatTemplate(it, null, false).getOrNull()?.formattedText
        } ?: prompt
        val config = GenerationConfig(
            maxTokens = maxTokens,
            // The parser wants one JSON object; stopping at the turn boundary keeps the model from
            // rambling into a second turn and wasting NPU time.
            stopWords = arrayOf("<|im_end|>", "<|endoftext|>"),
            stopCount = 2,
            // grammarString is honoured by runtimes that support constrained sampling and ignored
            // by those that do not, so passing the GBNF costs nothing and may save a fallback.
            samplerConfig = SamplerConfig(temperature = 0.1f, topP = 0.9f, grammarString = grammar),
        ).let { base ->
            if (imagePaths.isEmpty()) base else {
                val message = VlmChatMessage(
                    role = "user",
                    contents = imagePaths.map { VlmContent("image", it) },
                )
                wrapper.injectMediaPathsToConfig(arrayOf(message), base)
            }
        }

        val text = StringBuilder()
        var error: Throwable? = null
        wrapper.generateStreamFlow(formatted, config).collect { event ->
            when (event) {
                is LlmStreamResult.Token -> text.append(event.text)
                // Log.i, not Log.d: this device drops debug logs from third-party apps, and these
                // are the on-device benchmark numbers.
                is LlmStreamResult.Completed -> Log.i(
                    TAG,
                    "ttft=${event.profile.ttftMs}ms decode=${event.profile.decodingSpeed} tok/s " +
                        "prompt=${event.profile.promptTokens} gen=${event.profile.generatedTokens} " +
                        "stop=${event.profile.stopReason}",
                )
                is LlmStreamResult.Error -> error = event.throwable
            }
        }
        error?.let { throw it }
        // What the model actually said, before any parsing: the fastest way to see why a reply was
        // misread. Debug builds only, so a release build never logs what the user said.
        if (debuggable) Log.i(TAG, "raw=$text")
        return@withLock stripThinking(text.toString())
    }

    /**
     * Splits the ChatML string [LlamaIntentParser.buildPrompt] produces back into the system and
     * user turns, so the model's own template can be applied. Returns null when the prompt is not
     * in that shape, and the caller then sends it unchanged.
     */
    private fun asChatMessages(prompt: String, imagePaths: List<String>): Array<VlmChatMessage>? {
        // Every turn, in order. Taking only the first system and first user block (as this did
        // before conversation history was added) handed the model an *earlier* turn instead of
        // what the user had just said, so it kept answering a stale question.
        val turns = CHATML.all(prompt)
        if (turns.none { it.first == "system" } || turns.none { it.first == "user" }) return null
        val lastUser = turns.indexOfLast { it.first == "user" }
        return turns.mapIndexed { index, (role, text) ->
            // Images belong to the turn being asked about, which is the final user turn.
            val contents = if (index == lastUser) {
                imagePaths.map { VlmContent("image", it) } + VlmContent("text", text)
            } else {
                listOf(VlmContent("text", text))
            }
            VlmChatMessage(role = role, contents = contents)
        }.toTypedArray()
    }

    /**
     * Drops a `<think>…</think>` preamble. Qwen3 can emit one even when not asked to, and
     * [LlamaIntentParser.toCommand] takes the first `{` to the last `}` — reasoning text
     * containing braces would otherwise be parsed as the command.
     */
    private fun stripThinking(raw: String): String {
        val close = raw.indexOf("</think>")
        val body = if (close >= 0) raw.substring(close + "</think>".length) else raw
        return body.trim()
    }

    /** Frees the NPU session. The engine reloads on the next [ensureLoaded]. */
    fun release() {
        synchronized(loadLock) {
            vlm?.destroy()
            vlm = null
            loadFailed = false
        }
    }

    companion object {
        private const val TAG = "GenieXEngine"

        /**
         * Tried in order. Names are the hub's own, which are `qualcomm/…` — the `ai-hub-models/…`
         * spelling used by the CLI is only an alias and does not match a catalog listing.
         *
         * Qwen3-VL-4B first: multimodal, and small enough that a voice turn stays conversational.
         * The 2B-class Intern fallback keeps the NPU path working on a device that lacks it.
         */
        val PREFERRED_MODELS = listOf(
            "qualcomm/Qwen3-VL-4B-Instruct",
            "qualcomm/Intern3.5-VL-4B",
            "qualcomm/Intern3.5-VL-2B",
        )

        /** Snapdragon 8 Elite Gen 5 (iQOO 15). The plain 8 Elite is "SM8750". */
        const val DEFAULT_SOC = "SM8850"

        /**
         * null lets qairt pick its only compute unit (NPU). Set to "HTP0" if a device reports no
         * default accelerator — that is what Qualcomm's own sample app pins.
         */
        private val COMPUTE_UNIT: String? = null

        /** Pulls every `<|im_start|>role … <|im_end|>` block out of a ChatML prompt, in order. */
        private object CHATML {
            private val BLOCK = Regex(
                "<\\|im_start\\|>(system|user|assistant)\\n(.*?)<\\|im_end\\|>",
                RegexOption.DOT_MATCHES_ALL,
            )

            fun all(prompt: String): List<Pair<String, String>> =
                BLOCK.findAll(prompt).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        }
    }
}
