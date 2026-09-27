package com.example.kukoo.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    /** False for models the app works without. */
    val required: Boolean,
) {
    STT_PARAKEET(
        dirName = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
        label = "Speech recognition — accurate (Parakeet 110M)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
        bytes = 108_035_095,
        required = true,
    ),
    STT_MOONSHINE(
        dirName = "sherpa-onnx-moonshine-tiny-en-int8",
        label = "Speech recognition — small fallback (Moonshine tiny)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
        bytes = 107_600_538,
        required = false,
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
        required = true,
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

        // The partial archive is kept between attempts so a dropped connection resumes, not restarts.
        val archive = File(root, "${model.dirName}.tar.bz2.part")
        // A failed download keeps its partial file for the next try (see download()).
        download(model.url, archive, model.bytes) { got, total -> onProgress(got, total) }
        try {
            // The archive's own top-level folder is dirName, so unpack into models/ and it lands
            // at models/<dirName>/ — matching dirFor().
            extractTarBz2(archive, root)
            check(dest.isDirectory) { "archive did not contain ${model.dirName}/" }
            File(dest, MARKER).writeText(model.url)
        } catch (e: Exception) {
            // A complete download that will not unpack is corrupt: fetch it afresh next time.
            dest.deleteRecursively()
            throw e
        } finally {
            archive.delete()
        }
    }

    /** A large download over flaky wifi: retry, resuming from the bytes already on disk. */
    private suspend fun download(
        url: String,
        into: File,
        expected: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        var failure: Exception? = null
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            try {
                downloadOnce(url, into, expected, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "download attempt $attempt/$DOWNLOAD_ATTEMPTS failed: ${e.message}")
                delay(RETRY_DELAY_MS)
            }
        }
        throw failure ?: IOException("download failed")
    }

    private suspend fun downloadOnce(
        url: String,
        into: File,
        expected: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        val have = if (into.isFile) into.length() else 0L
        // GitHub redirects release downloads to objects.githubusercontent.com; both are https so
        // HttpURLConnection follows it.
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            // 416: the file on disk is already all of it.
            if (code == 416 && have > 0) {
                onProgress(have, have)
                return
            }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            check(code == HttpURLConnection.HTTP_OK || resumed) { "HTTP $code for $url" }
            val remaining = conn.contentLengthLong
            val total = when {
                remaining <= 0 -> expected
                resumed -> have + remaining
                else -> remaining
            }
            var got = if (resumed) have else 0L
            var lastReported = got
            conn.inputStream.use { input ->
                // A server that ignored the Range header sent the whole file: start over.
                FileOutputStream(into, resumed).use { out ->
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
            // A stream that ends early without an exception would otherwise be unpacked as-is.
            if (remaining > 0) check(got >= total) { "download ended early ($got of $total bytes)" }
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
        private const val DOWNLOAD_ATTEMPTS = 30
        private const val RETRY_DELAY_MS = 2_000L

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
