package com.incognitoblocker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local record of block events.
 *
 * Nothing is sent to a server. The log lives on the device and disappears with
 * the app. Showing that a monitoring tool does not quietly exfiltrate data is
 * part of earning the trust it needs to be useful.
 *
 * What is stored: timestamp, browser, and which signal matched.
 * What is NOT stored: URLs, page contents, or anything typed.
 */
object EventLog {

    private const val FILE_NAME = "block_events.json"
    private const val MAX_ENTRIES = 500

    data class Entry(
        val timestamp: Long,
        val packageName: String,
        val browserLabel: String,
        val signal: String,
        /** Diagnostic samples are rendered differently and are not blocks. */
        val isDiagnostic: Boolean
    ) {
        fun formattedTime(): String =
            SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    @Synchronized
    fun append(
        ctx: Context,
        packageName: String,
        browserLabel: String,
        signal: String,
        isDiagnostic: Boolean = false
    ) {
        val file = File(ctx.filesDir, FILE_NAME)
        val arr = readArray(file)

        arr.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("pkg", packageName)
                .put("browser", browserLabel)
                .put("signal", signal)
                .put("diag", isDiagnostic)
        )

        val trimmed = if (arr.length() > MAX_ENTRIES) {
            JSONArray().also { out ->
                for (i in (arr.length() - MAX_ENTRIES) until arr.length()) out.put(arr.get(i))
            }
        } else arr

        runCatching { file.writeText(trimmed.toString()) }
    }

    @Synchronized
    fun readAll(ctx: Context): List<Entry> {
        val arr = readArray(File(ctx.filesDir, FILE_NAME))
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    timestamp = o.optLong("t"),
                    packageName = o.optString("pkg"),
                    browserLabel = o.optString("browser"),
                    signal = o.optString("signal"),
                    isDiagnostic = o.optBoolean("diag", false)
                )
            )
        }
        return out.asReversed()
    }

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE_NAME).delete() }
    }

    private fun readArray(file: File): JSONArray =
        runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
}
