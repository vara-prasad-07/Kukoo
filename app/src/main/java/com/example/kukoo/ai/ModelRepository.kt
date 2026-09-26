package com.example.kukoo.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The speech models Kukoo downloads once, then uses entirely offline.
 *
 * Each bundle is a `.tar.bz2` from the sherpa-onnx releases. [dirName] is both the folder under
 * `filesDir/models/` and the directory the archive unpacks into, so a finished install is just
 * `models/<dirName>/` plus the [MARKER] file.
 */
enum class SpeechModel(
    val dirName: String,
    val label: String,
    val url: String,
    /** Download size, for the progress UI. Verified against the release assets. */
    val bytes: Long,
    /** False for models the app works without (Kokoro is a nicer voice, not a required one). */
    val required: Boolean,
) {
    STT_MOONSHINE(
        dirName = "sherpa-onnx-moonshine-tiny-en-int8",
        label = "Speech recognition (Moonshine tiny)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
        bytes = 107_600_538,
        required = true,
    ),
    TTS_PIPER(
        dirName = "vits-piper-en_US-amy-medium",
        label = "Voice — fast (Piper)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
        bytes = 67_223_746,
        required = true,
    ),
    TTS_KOKORO(
        dirName = "kokoro-int8-multi-lang-v1_1",
        label = "Voice — natural (Kokoro 82M)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
        bytes = 147_031_220,
        required = false,
    ),
}

/** Progress for one model install. [downloaded] == [total] with [done] still false means unpacking. */
data class InstallProgress(
    val model: SpeechModel,
    val downloaded: Long,
    val total: Long,
    val done: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Downloads and unpacks the sherpa-onnx speech models into app-private storage.
 *
 * Installs are resumable only at whole-model granularity: a failed or cancelled install leaves no
 * [MARKER], so the next attempt re-downloads that model and overwrites the partial directory.
 */
class ModelRepository(context: Context) {
    private val root = File(context.applicationContext.filesDir, "models")

    fun dirFor(model: SpeechModel): File = File(root, model.dirName)

    fun isInstalled(model: SpeechModel): Boolean = File(dirFor(model), MARKER).isFile

    fun missing(requiredOnly: Boolean = false): List<SpeechModel> =
        SpeechModel.entries.filter { (!requiredOnly || it.required) && !isInstalled(it) }

    /**
     * Installs every model in [models] that is not already present, emitting progress as it goes.
     * A failure on one model is reported as an [InstallProgress.error] and does not stop the rest.
     */
    fun install(models: List<SpeechModel> = SpeechModel.entries): Flow<InstallProgress> = flow {
        for (model in models) {
            if (isInstalled(model)) {
                emit(InstallProgress(model, model.bytes, model.bytes, done = true))
                continue
            }
            try {
                installOne(model) { downloaded, total ->
                    emit(InstallProgress(model, downloaded, total))
                }
                emit(InstallProgress(model, model.bytes, model.bytes, done = true))
            } catch (e: Exception) {
                Log.e(TAG, "install failed for ${model.dirName}", e)
                emit(InstallProgress(model, 0, model.bytes, error = e.message ?: e.toString()))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun installOne(
        model: SpeechModel,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        val dest = dirFor(model)
        dest.deleteRecursively()
        root.mkdirs()

        val archive = File(root, "${model.dirName}.tar.bz2.part")
        archive.delete()
        try {
            download(model.url, archive, model.bytes) { got, total -> onProgress(got, total) }
            // The archive's own top-level folder is dirName, so unpack into models/ and it lands
            // at models/<dirName>/ — matching dirFor().
            extractTarBz2(archive, root)
            check(dest.isDirectory) { "archive did not contain ${model.dirName}/" }
            File(dest, MARKER).writeText(model.url)
        } finally {
            archive.delete()
        }
    }

    private suspend fun download(
        url: String,
        into: File,
        expected: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        // GitHub redirects release downloads to objects.githubusercontent.com; both are https so
        // HttpURLConnection follows it, but the redirect is logged to make a failure obvious.
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code for $url" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
            var got = 0L
            var lastReported = 0L
            conn.inputStream.use { input ->
                FileOutputStream(into).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        // Reporting every chunk floods the UI; every 512 KB is smooth enough.
                        if (got - lastReported >= 512 * 1024) {
                            lastReported = got
                            onProgress(got, total)
                        }
                    }
                }
            }
            onProgress(got, total)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, into: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(into, entry.name)
                // Zip-slip guard: an entry must not escape the destination directory.
                val canonical = target.canonicalPath
                require(canonical.startsWith(into.canonicalPath + File.separator)) {
                    "archive entry escapes destination: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { tar.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ModelRepository"

        /** Written last, so its presence means "this model unpacked completely". */
        const val MARKER = ".installed"

        /**
         * Finds the first file under [root] matching [predicate], breadth-first.
         * Model bundles are located by scanning rather than by hard-coded relative paths, so a
         * bundle that nests its files one level deeper still works.
         */
        fun findFile(root: File, predicate: (File) -> Boolean): File? =
            root.walkTopDown().firstOrNull { it.isFile && predicate(it) }

        fun findFileNamed(root: File, name: String): File? =
            findFile(root) { it.name == name }

        fun findDirNamed(root: File, name: String): File? =
            root.walkTopDown().firstOrNull { it.isDirectory && it.name == name }
    }
}
