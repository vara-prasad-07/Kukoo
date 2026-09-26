package com.example.kukoo.domain

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Sends the [PlanRequest] to the laptop ("Office Kit") as JSON and returns its plan tagged
 * [PlanSource.OFFICE_KIT]. Any failure (unreachable, timeout, HTTP error, malformed reply)
 * falls straight back to [fallback], so replanning always works offline.
 *
 * Default endpoint is the host machine as seen from the Android emulator; pass the laptop's
 * LAN address (e.g. `http://192.168.1.20:8080/replan`) for a real phone.
 *
 * Request:  {"scope","date","now","window":{start,end},"fixedBlocks":[{start,end}],
 *            "tasks":[{id,title,deadline,durationMin,priority,status,createdAt,completedAt}]}
 * Response: {"blocks":[{taskId,title,priority,start,end,part,partCount}],
 *            "conflicts":[{taskId,title,kind,minutes,deadline}],
 *            "window":{start,end}?, "generatedAt":long?}   (times are epoch millis)
 */
class OfficeKitReplanner(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val fallback: Replanner = LocalReplanner(),
    private val timeoutMs: Int = 4_000,
) : Replanner {

    override suspend fun replan(request: PlanRequest): Plan {
        val remote = try {
            // Hard cap on top of the socket timeouts so a stalled connect can't hang the turn.
            withTimeout(timeoutMs * 2L) { withContext(Dispatchers.IO) { post(request) } }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
        } catch (e: Exception) {
            Log.w(TAG, "Office Kit unavailable, replanning locally: ${e.message}")
            null
        }
        return remote ?: fallback.replan(request)
    }

    private fun post(request: PlanRequest): Plan {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(encode(request).toString().toByteArray(Charsets.UTF_8)) }

            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return decode(JSONObject(body), request)
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(r: PlanRequest) = JSONObject().apply {
        put("scope", r.scope.name)
        put("date", r.date.toString())
        put("now", r.now)
        put("window", range(r.window))
        put("fixedBlocks", JSONArray(r.fixedBlocks.map(::range)))
        put("tasks", JSONArray(r.tasks.map { t ->
            JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("deadline", t.deadline ?: JSONObject.NULL)
                put("durationMin", t.durationMin)
                put("priority", t.priority.name)
                put("status", t.status.name)
                put("createdAt", t.createdAt)
                put("completedAt", t.completedAt ?: JSONObject.NULL)
            }
        }))
    }

    private fun range(r: TimeRange) = JSONObject().put("start", r.start).put("end", r.end)

    private fun decode(json: JSONObject, request: PlanRequest): Plan {
        val blocks = json.getJSONArray("blocks").objects().map { b ->
            ScheduledBlock(
                taskId = b.getLong("taskId"),
                title = b.getString("title"),
                priority = Priority.fromName(b.optString("priority")),
                start = b.getLong("start"),
                end = b.getLong("end"),
                part = b.optInt("part", 1),
                partCount = b.optInt("partCount", 1),
            ).also { require(it.end > it.start) { "empty block" } }
        }
        val conflicts = json.optJSONArray("conflicts")?.objects().orEmpty().map { c ->
            Conflict(
                taskId = c.getLong("taskId"),
                title = c.getString("title"),
                kind = ConflictKind.valueOf(c.getString("kind").uppercase()),
                minutes = c.getInt("minutes"),
                deadline = if (c.isNull("deadline")) null else c.optLong("deadline"),
            )
        }
        val window = json.optJSONObject("window")?.let { TimeRange(it.getLong("start"), it.getLong("end")) }
            ?: request.window
        return Plan(
            scope = request.scope,
            date = request.date,
            window = window,
            blocks = blocks,
            conflicts = conflicts,
            source = PlanSource.OFFICE_KIT,
            generatedAt = json.optLong("generatedAt", request.now),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    companion object {
        const val DEFAULT_ENDPOINT = "http://10.0.2.2:8080/replan"
        private const val TAG = "OfficeKitReplanner"
    }
}
