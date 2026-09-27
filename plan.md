# Kukoo — Fully Offline On-Device AI Plan

**Target device:** iQOO 15 · Snapdragon 8 Elite Gen 5 · chipset id `SM8850`
**Constraint:** everything runs on-device. Network is used exactly once, on first launch, to pull models.
**Budget:** 8–24 hours to a demo.

> **Status: running on the iQOO 15.** Qwen3-VL-4B-Instruct is downloaded, loaded on the Hexagon
> NPU, and parsing commands end to end. Measured on device:
>
> | | |
> |---|---|
> | Time to first token | **389 ms** |
> | Decode | **18.5 tok/s** |
> | Cold NPU load | **9.4 s** (preloaded at app start) |
> | Bundle size | 2.9 GB |
>
> For comparison, the only published figure for Qwen2.5-VL-7B is ~7.7 tok/s — the 4B is **2.4×
> faster**, and a 58-token command parse completes in ~3.5 s, inside the parser's 8 s timeout.
>
> Wired and verified: GenieX + Qwen3-VL-4B on the NPU behind `LlamaIntentParser`, sherpa-onnx
> Moonshine STT, sherpa-onnx Kokoro TTS (103 voices @ 24 kHz), and a model-setup screen at
> **Home → ⋮ → AI models…**. Not yet built: the camera capture UI for photo-to-tasks (Phase 4) —
> `GenieXEngine.generate()` already accepts `imagePaths`, so only the screen and the review list
> are missing. Versions: `geniex-android:0.7.0`, `sherpa-onnx-1.13.8.aar`.

---

## 1. What we already have (and why that's lucky)

The base app is not a shell — it's a finished, tested product with **three deliberate AI seams**
and nothing else to untangle. `app/src/main/java/com/example/kukoo/ai/AiSeams.kt`:

```kotlin
interface IntentParser  { suspend fun parse(utterance: String, context: ParseContext): TaskCommand }
interface SpeechToText  { val isReady: Boolean; suspend fun listen(onCaptured: () -> Unit = {}): String }
interface Speaker       { suspend fun speak(text: String); fun stop() }
```

And `KukooApp.kt` wires them in **three lines**:

```kotlin
val parser:  IntentParser = LlamaIntentParser(appContext)
val stt:     SpeechToText = AndroidSpeechToText(appContext)
val speaker: Speaker      = AndroidSpeaker(appContext)
```

That is the entire surface area of this project. We are swapping three implementations behind
interfaces that already have tests, timeouts, cancellation, and fallbacks around them.

Also already built and working — do not touch:

| Piece | Where | Note |
|---|---|---|
| Ringing call over lock screen | `call/` | full-screen intent, ringer, 30s timeout, boot-restore |
| Task engine + validation | `domain/TaskEngine.kt` | 516 lines, mutex'd, undo support |
| Deterministic spoken replies | `EngineResult.spoken` | **the assistant's exact words** |
| Rule-based parser | `ai/RuleBasedIntentParser.kt` | 361 lines, the safety net |
| GBNF grammar for the JSON | `ai/LlamaGrammar.kt` | constrains llama.cpp sampling |
| Vendored llama.cpp + JNI | `cpp/kukoo_llama.cpp` | already compiles, CPU inference |
| SQLite store, Compose UI, 8 test files | `data/`, `ui/`, `test/` | |

### The one rule that must survive

`TaskEngine` produces the sentence the assistant speaks. **The LLM never writes user-facing prose
and never mutates state** — it only maps an utterance onto one `TaskCommand`, which the engine then
validates and may reject. Keep this. It's why the demo can't hallucinate a task it didn't create,
and it's a genuinely good answer when a judge asks "how do you stop it making things up?"

---

## 2. Model choice — one change to your plan

You proposed `ai-hub-models/Qwen2.5-VL-7B-Instruct`. **Use `ai-hub-models/Qwen3-VL-4B-Instruct` instead.**

| | Qwen2.5-VL-7B (your pick) | Qwen3-VL-4B (recommended) |
|---|---|---|
| In the GenieX catalog? | Not listed — the docs list Qwen3-4B, the **Qwen3-VL family**, Phi-4-mini, Llama-3.2-3B | Yes, with a published AI Hub mobile page |
| SM8850 bundle? | Unverified | Page lists **Snapdragon 8 Elite Gen 5** |
| Decode speed | ~7.6–7.7 tok/s (measured on a Dragonwing IQ-9075 board, ~1 s TTFT) | Unpublished, but ~4B vs ~7B should be roughly 1.5–2× faster |
| Multimodal | Yes | Yes — ViT vision encoder, so the whiteboard feature still works |

Why the speed matters more than it looks: the intent JSON is ~30–60 tokens. At 7.7 tok/s that is
**4–8 seconds of decode alone**, before STT and TTS. `LlamaIntentParser` already gives up at 8 s
(`timeoutMs = 8_000`), so a 7B would spend the whole demo silently falling back to the rule parser —
you'd carry 5 GB of model weights and get zero benefit from them. A 4B keeps the call conversational.

One VLM covers **both** features, so there's one download and one model load:
- voice loop → text-only prompt → intent JSON
- whiteboard photo → image prompt → list of tasks

> Treat the 7.7 tok/s figure as indicative, not gospel — it's from a dev board, not a phone, and
> it's the only public number I could find. Measure on the iQOO in Phase 3 and pick 4B vs 2B from
> your own numbers. `Qwen3-VL-2B-Instruct` also exists and is the escape hatch if 4B disappoints.

### Things about `qairt` that will bite if you don't plan for them

1. **Grammar may survive after all.** *Correcting an earlier draft of this plan:* GenieX's
   `SamplerConfig` has a `grammarString` field ("BNF-like grammar to constrain outputs"), so
   `LlamaGrammar.COMMAND_JSON` is passed through rather than dropped. Whether the `qairt` plugin
   honours it or silently ignores it is unverified — passing it costs nothing either way. Treat the
   fallback as the thing that guarantees correctness: `LlamaIntentParser.toCommand()` returns
   `null` on malformed JSON and the caller falls through to `RuleBasedIntentParser`. **That cascade
   is load-bearing — do not delete the rule parser.**
2. **`ModelConfig()` defaults are rejected by qairt.** The context length is baked into the
   pre-compiled bundle, so `nCtx` and `nGpuLayers` must both be `0`. Passing the bare
   `ModelConfig()` from the original snippet fails at model creation.
3. **Cold load is slow** (tens of seconds for an NPU bundle). Load the model once at app start,
   off the main thread, and keep it alive for the process lifetime. Never load it inside a turn —
   the parser's 8 s timeout would fire every time and you'd never see the LLM run.
4. **Send messages, not a raw prompt string.** `applyChatTemplate` makes `<|im_start|>` land as
   real special tokens; handing `generateStreamFlow` a hand-built ChatML string tokenizes those
   markers as literal text and quality drops without any error.

---

## 3. Speech: recommendation

**Use sherpa-onnx for both STT and TTS.** One Apache-2.0 AAR, one native stack, both directions.

### TTS — Kokoro-82M (primary), Piper (bundled fallback)

You asked for a natural voice. Kokoro-82M is the best-sounding thing that runs comfortably on a
phone CPU; it is well-supported in sherpa-onnx and is widely used in shipped offline Android TTS
apps. Piper voices are smaller and faster but audibly more robotic.

Plan: **download Kokoro on first run, and bundle one small Piper voice in `assets/`** as the
guaranteed-works-offline fallback. If Kokoro isn't present or is too slow on the day, the app still
talks. Cost is ~60–80 MB of APK.

### STT — sherpa-onnx offline recognizer

Press-and-hold already exists in the UI, which maps cleanly onto a non-streaming offline
recognizer: `AudioRecord` at 16 kHz mono → accumulate `FloatArray` → decode on release. Use a
Moonshine-tiny or Whisper-tiny/base English model; short command utterances are exactly their
strength.

**Rejected: Qualcomm AI Hub Whisper.** It's real and NPU-accelerated, but it's a completely separate
QNN/TFLite native integration from GenieX — a second native stack to build, for no meaningful
accuracy gain on 3-second commands. Not worth it in 24 hours.

**Note the dead weight already in the repo:** `assets/models/vosk-model-small-en-us-0.15/` (~40 MB)
is present but there is no Vosk dependency and no code referencing it. It's a ready-made safety net —
see the decision gate in §6.

---

## 4. Architecture of the additions

Nothing below changes `TaskEngine`, `TaskCommand`, the UI, or the call machinery.

```
ai/
  GenieXEngine.kt          NEW  GenieX lifecycle: init, pull, load, generate. Implements LlamaEngine.
  VisionTaskExtractor.kt   NEW  Bitmap -> List<TaskCommand.AddTask>
  SherpaSpeechToText.kt    NEW  implements SpeechToText
  SherpaSpeaker.kt         NEW  implements Speaker
  ModelRepository.kt       NEW  download + unpack + progress for the sherpa models
ui/setup/
  ModelSetupScreen.kt      NEW  first-run download UI
ui/capture/
  CaptureReviewScreen.kt   NEW  confirm extracted tasks before committing
```

### The cheapest possible LLM swap

`LlamaIntentParser` takes a `LlamaEngine` in its primary constructor:

```kotlin
interface LlamaEngine {
    fun ensureLoaded(): Boolean
    fun complete(prompt: String, maxTokens: Int, grammar: String? = null): String
}
```

So write `GenieXEngine : LlamaEngine`, ignore the `grammar` argument, and the entire swap is:

```kotlin
val parser: IntentParser = LlamaIntentParser(GenieXEngine(appContext))
```

All the timeout / mutex / fallback / JSON-validation logic is reused as-is. Do this before writing
anything new.

### Vision flow (deliberately off the voice path)

A VLM in a live call is a latency disaster; a VLM behind a "scanning…" spinner is a great demo.
Keep it as its own gesture:

1. Camera button on Home → capture a photo of a whiteboard / handwritten list.
2. `VisionTaskExtractor` prompts the VLM for a **JSON array** of tasks.
3. Parse each element with the *same* `toCommand()` logic → `List<TaskCommand.AddTask>`.
4. **`CaptureReviewScreen` shows them as checkboxes.** The user confirms; then each one goes
   through `engine.execute()` normally.

Step 4 is not optional polish — it is what makes an unreliable extraction safe, it reuses
`AddTask` so the engine needs no new code, and on stage "here's what I read, confirm?" looks
deliberate rather than flaky.

### First-run setup

- `GenieXSdk.getInstance().init(context)` at app start.
- `ModelManagerWrapper.pullFlow(...)` for the VLM, `ModelRepository` for the sherpa models.
- Add `Screen.SETUP` to the `Screen` enum in `ui/AppState.kt`; gate `HOME` behind it until models
  are ready.
- `SpeechToText.isReady` already exists and the UI already degrades gracefully to typed input when
  it's false — so a half-downloaded app is still usable. That's free; don't break it.

---

## 5. Build order (hour estimates, ~14 h of work in a 24 h window)

**Phase 0 — de-risk first (1 h).** Do this before writing any feature code.
- Add the GenieX dependency, `init()`, and **`pullFlow` for `Qwen3-VL-4B-Instruct` with `chipset = "SM8850"`**. Log progress. Confirm the bundle exists and downloads.
- Fix `abiFilters`: drop `x86_64` (GenieX is arm64-only) or expect link failures.
- Check GenieX's `minSdk` against the app's `26` — raise if needed.
- Verify the Gradle coordinate. The README shows `com.qualcomm.qti:geniex-android:0.4.0` but also
  notes Maven publishing was "TODO", so be ready to drop the AAR into `app/libs/`.
- **If the pull fails, stop and switch to the `llama_cpp` runtime** — you already have llama.cpp
  vendored and compiling. Knowing this in hour 1 instead of hour 12 is the single highest-value
  thing in this plan.

**Phase 1 — TTS (2–3 h).** Biggest perceived win per hour; the app starts *talking*.
- sherpa-onnx AAR, `SherpaSpeaker : Speaker`, Piper voice from assets first (no network).
- Honour the existing contract exactly: `speak()` suspends until the audio finishes, `stop()`
  cancels mid-utterance. `AndroidSpeaker.kt` shows the semantics — the interrupt-on-mic-press path
  in `KukooViewModel.interruptSpeech()` depends on it.
- Then swap in Kokoro and A/B the two.

**Phase 2 — STT (2–3 h).** `SherpaSpeechToText`, `AudioRecord` 16 kHz mono, offline decode on release.

**Phase 3 — LLM on NPU (3–4 h).** `GenieXEngine : LlamaEngine`, one-line container swap, load at
startup off-main-thread. Tune the prompt to compensate for the missing grammar. **Measure TTFT and
tok/s on the iQOO and write the numbers down** — judges ask, and it decides 4B vs 2B.

**Phase 4 — Vision (2–3 h).** Camera capture → `VisionTaskExtractor` → `CaptureReviewScreen`.

**Phase 5 — First-run setup screen (1–2 h).** Progress UI over `pullFlow` + `ModelRepository`.

**Phase 6 — demo hardening (1–2 h).** See §7.

---

## 6. Decision gates

Pre-committed calls, so you don't burn time deliberating at 3 a.m.

- **Phase 0 fails (no `SM8850` bundle / SDK won't resolve)** → switch `runtime_id` to `llama_cpp`
  with a Q4_0 GGUF, or fall back to the vendored llama.cpp. You keep grammar support that way, and
  the CPU path still demos.
- **Behind schedule entering Phase 2** → wire **Vosk** for STT instead (dependency
  `com.alphacephei:vosk-android`; the model is *already in `assets/`*, ~20 lines of code, maybe 1 h).
  Lower accuracy, far less risk. Spend the saved time on the LLM.
- **Qwen3-VL-4B too slow in Phase 3** → drop to `Qwen3-VL-2B-Instruct`. The parser's 8 s timeout
  means a slow model is *invisible* rather than broken, so this is a comfort call, not a crisis.
- **Kokoro too slow or too big** → ship the bundled Piper voice. Already your fallback.
- **Out of time entirely** → Phases 1–2 alone (natural voice + offline STT, rule-based parsing)
  is still a complete, fully-offline demo. The LLM is the upgrade, not the product.

---

## 7. Demo-day hardening — read this before you sleep

1. **Pre-download every model onto the phone tonight.** Multi-GB pulls over shared venue wifi is
   the most likely way this demo dies. GenieX's `pullFlow` can import models from local storage
   with no network, so keep an `adb push` sideload path and test it cold.
2. **Airplane mode for the demo.** It's the whole pitch — turn the radios off on stage and let the
   app keep working. Rehearse it that way so nothing secretly depends on the network.
3. **Disable battery optimization and pin thermals.** `call/BatteryOptimization.kt` already exists;
   sustained NPU inference throttles, so don't run a benchmark loop right before you present.
4. **Rehearse the exact utterances.** Write down 6–8 commands you know parse correctly. The rule
   parser's tests (`RuleBasedIntentParserTest.kt`) are a free source of known-good phrasings.
5. **Keep the typed-input fallback visible.** It's already in `VoiceSessionScreen`. If the mic
   fails on stage, type it and keep talking — nobody will notice.
6. **Run `./gradlew test` before you commit anything.** There are 8 test files covering the engine
   and parser; they'll catch a seam swap that broke the contract.

---

## 8. Answering the obvious judge questions

- *"How is this private?"* Nothing leaves the device after setup. Demo in airplane mode.
- *"How do you stop hallucination?"* The LLM emits only a constrained command; `TaskEngine`
  validates it and writes every user-facing sentence. Show `EngineResult.spoken`.
- *"What if the model fails?"* Three-tier cascade: NPU LLM → rule-based parser → typed input. Pull
  the model file off the device and it still works.
- *"Why on-device rather than an API?"* NPU, works in airplane mode, zero inference cost, no audio
  ever uploaded.

---

## 9. Sources

- [GenieX (Qualcomm)](https://github.com/qualcomm/GenieX) — runtimes, chipsets, Gradle coordinate
- [GenieX Android bindings README](https://github.com/qualcomm/GenieX/blob/main/bindings/android/README.md)
- [GenieX supported models](https://geniex.aihub.qualcomm.com/en/models/supported)
- [Qwen3-VL-4B-Instruct on AI Hub](https://aihub.qualcomm.com/mobile/models/qwen3_vl_4b_instruct)
- [Qwen3-VL-2B-Instruct on AI Hub](https://aihub.qualcomm.com/models/qwen3_vl_2b_instruct)
- [Qwen2.5-VL-7B on a Qualcomm board — the 7.7 tok/s figure](https://www.macnica.co.jp/en/business/semiconductor/articles/qualcomm/150066/)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — STT + TTS, Android AAR
- [Whisper-Small-En on AI Hub](https://aihub.qualcomm.com/mobile/models/whisper_small_en) — the rejected alternative

---

## 10. Adding a task by conversation (implemented)

A spoken or typed "add" needs **a name, a deadline, a duration and a priority**. The assistant asks for
whatever is missing, one question at a time, and nothing is saved until all four are known.
(Forms are unchanged: they still add immediately with defaults.)

```
you:  add                       →  What should I call the task?
you:  call mom                  →  Got it: Call mom. When is Call mom due?
you:  tomorrow at 6             →  Got it: due tomorrow at 6 PM. How long will Call mom take?
you:  half an hour              →  Got it: 30 minutes. Is Call mom high, medium or low priority?
you:  high                      →  Added Call mom, due tomorrow at 6 PM, 30 minutes. High priority.
```

- **Everything said in one sentence is added at once**; a partial sentence is only asked for what is missing.
- **Mid-question** you can ask something else ("what's due today" is answered, then "Back to Call mom. …"),
  say "never mind" to drop it, or start a different task (the old one is dropped, and it says so).
- **No canned examples.** The few-shot tasks are gone from the prompt. The bare "add" bug is prevented in
  code as well: `ai/Grounding.kt` drops any name, time, duration or priority the model returns that the
  user's own words do not support, so the assistant asks instead of inventing.
- **Verified on the iQOO 15** with the real model (`raw=` output in logcat): the model tried to fill in
  `duration_min: 30, priority: medium` for "add water plants every day at 8 am" and answered "i dont know"
  with `duration_min: 90`; both were dropped and the question was asked again.
- The dialog also works without the NPU: the rule parser reads replies in context (`parseReply`).
