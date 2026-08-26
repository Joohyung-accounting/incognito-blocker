package com.incognitoblocker.core

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.incognitoblocker.R
import java.util.ArrayDeque

/**
 * Walks the accessibility node tree once and scores the incognito signals it finds.
 *
 * Two principles drive the design:
 *
 *  1) Page CONTENT and browser CHROME are kept apart.
 *     Nobody should get blocked for reading an article about private browsing,
 *     so by default signals are only matched against the browser's own UI
 *     (toolbar, menu, tab list). The single exception is a signal explicitly
 *     marked `matchPageContent = true`.
 *     That exception is not optional: Chrome renders its incognito start page
 *     ("You've gone Incognito") as web content, so without it an incognito tab
 *     would never be detected at all.
 *     Inside page content only text is read — view IDs and content descriptions
 *     there can be authored by the page and are not trusted.
 *
 *  2) Signals have exactly two tiers.
 *     weight 2 — exists ONLY while private mode is active (badge, "turn off"
 *                menu entry, private-only view IDs)
 *     weight 1 — merely names the mode ("Secret mode", "InPrivate", …), which
 *                also appears in the normal-mode menu ("Turn on Secret mode")
 *     A weight-1 signal can therefore never trigger a block on its own.
 *
 * Performance matters too: onAccessibilityEvent fires dozens of times per
 * scroll, so the traversal is bounded in both node count and depth and the tree
 * is walked exactly once.
 */
object IncognitoDetector {

    /** Node budget per evaluation — guards against huge trees and battery drain. */
    private const val MAX_NODES = 600

    /** Maximum traversal depth. */
    private const val MAX_DEPTH = 30

    /** Sentinel score used for browsers that are private by their very nature. */
    private const val ALWAYS_PRIVATE_SCORE = 99

    /** Marker recorded in the log when an always-private browser is blocked. */
    const val ALWAYS_PRIVATE_MARKER = "always-private-browser"

    /**
     * A node whose class name matches one of these starts a page-content subtree.
     * Everything below it is web page content, not browser UI.
     */
    private val CONTENT_CLASS_MARKERS = arrayOf(
        "android.webkit.WebView",
        "android.widget.WebView"
    )

    data class Result(
        val detected: Boolean,
        val score: Int,
        /** Which signals matched — used for the log and for debugging. */
        val matchedSignals: List<String>
    ) {
        companion object {
            val NONE = Result(false, 0, emptyList())
        }
    }

    /**
     * What a single traversal collected.
     *
     * Browser UI and page content are kept in separate buckets so that a signal
     * can opt in to page content without every signal being exposed to it.
     */
    private class Collected {
        val viewIds = HashSet<String>()
        val descriptions = ArrayList<String>()
        val texts = ArrayList<String>()

        /** Text from inside web page content. Only `matchPageContent` signals see this. */
        val pageTexts = ArrayList<String>()
    }

    fun detect(root: AccessibilityNodeInfo?, rule: BrowserRules.Rule): Result {
        if (rule.alwaysPrivate) {
            return Result(true, ALWAYS_PRIVATE_SCORE, listOf(ALWAYS_PRIVATE_MARKER))
        }
        if (root == null || rule.signals.isEmpty()) return Result.NONE

        val found = collect(root)

        var score = 0
        val matched = ArrayList<String>()

        for (signal in rule.signals) {
            if (!matches(signal, found)) continue
            score += signal.weight
            matched.add("${signal.kind}:${signal.value}(+${signal.weight})")
            if (score >= BrowserRules.SCORE_THRESHOLD) break
        }

        return Result(
            detected = score >= BrowserRules.SCORE_THRESHOLD,
            score = score,
            matchedSignals = matched
        )
    }

    private fun matches(signal: BrowserRules.Signal, found: Collected): Boolean =
        when (signal.kind) {
            // View IDs and content descriptions are always taken from browser UI
            // only. A web page must never be able to forge a detection signal.
            BrowserRules.Kind.VIEW_ID_SUFFIX ->
                found.viewIds.any { it.endsWith(signal.value) }

            BrowserRules.Kind.CONTENT_DESC ->
                found.descriptions.any { it.contains(signal.value, ignoreCase = true) }

            BrowserRules.Kind.TEXT_EXACT ->
                textPool(signal, found).any { it.equals(signal.value, ignoreCase = true) }

            BrowserRules.Kind.TEXT_CONTAINS ->
                textPool(signal, found).any { it.contains(signal.value, ignoreCase = true) }
        }

    private fun textPool(signal: BrowserRules.Signal, found: Collected): List<String> =
        if (signal.matchPageContent) found.texts + found.pageTexts else found.texts

    /**
     * Breadth-first traversal collecting view IDs, content descriptions and text
     * in a single pass. Once the walk enters a WebView subtree everything below
     * it is classified as page content.
     */
    private fun collect(root: AccessibilityNodeInfo): Collected {
        val out = Collected()
        // (node, depth, already inside page content)
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Boolean>>()
        queue.add(Triple(root, 0, false))
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth, inPageAlready) = queue.poll() ?: break
            visited++

            val inPage = inPageAlready || isPageContent(node)

            runCatching {
                if (inPage) {
                    node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { out.pageTexts.add(it) }
                } else {
                    node.viewIdResourceName?.let { out.viewIds.add(it) }
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { out.descriptions.add(it) }
                    node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { out.texts.add(it) }
                }
            }

            if (depth >= MAX_DEPTH) continue

            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.add(Triple(child, depth + 1, inPage))
            }
        }

        return out
    }

    private fun isPageContent(node: AccessibilityNodeInfo): Boolean {
        val cls = runCatching { node.className?.toString() }.getOrNull() ?: return false
        return CONTENT_CLASS_MARKERS.any { cls == it } || cls.endsWith("WebView")
    }

    /**
     * Diagnostic helper — dumps what is actually readable on the current screen.
     *
     * Detection rules differ per browser version and per language. Samsung
     * Internet in particular exposes no policy for disabling secret mode, so
     * these strings are the only lead available. Turn diagnostic mode on, open
     * private mode in the browser, and add whatever shows up here to
     * [BrowserRules].
     *
     * Collection works exactly as in [detect], so what appears here is precisely
     * what the matcher sees. The PAGE line is web page content, which only
     * `matchPageContent` signals are allowed to match against.
     */
    fun sample(ctx: Context, root: AccessibilityNodeInfo?): String {
        if (root == null) return ctx.getString(R.string.log_screen_unreadable)
        val found = collect(root)
        val none = ctx.getString(R.string.log_none)

        // The suffix alone identifies a view ID and is far easier to read.
        val idSuffixes = found.viewIds
            .mapNotNull { it.substringAfter(":", "").ifBlank { null } }
            .distinct().take(16)
        val descSample = found.descriptions.map { it.take(40) }.distinct().take(12)
        val textSample = found.texts.filter { it.length in 1..40 }.distinct().take(16)
        val pageSample = found.pageTexts.filter { it.length in 1..40 }.distinct().take(8)

        return buildString {
            append("ID: ").append(idSuffixes.joinToString(" | ").ifBlank { none })
            append("\nDESC: ").append(descSample.joinToString(" | ").ifBlank { none })
            append("\nTEXT: ").append(textSample.joinToString(" | ").ifBlank { none })
            append("\nPAGE: ").append(pageSample.joinToString(" | ").ifBlank { none })
        }
    }

    /**
     * Notification-based detection.
     *
     * Chrome and friends post a persistent notification for as long as incognito
     * tabs exist. Once a page has loaded the on-screen signals get weak, so this
     * is the practical fallback for noticing that a session is still running.
     */
    fun matchesNotification(rule: BrowserRules.Rule, title: String?, text: String?): Boolean {
        if (rule.notificationSignals.isEmpty()) return false
        val haystack = listOfNotNull(title, text).joinToString(" ")
        if (haystack.isBlank()) return false
        return rule.notificationSignals.any { haystack.contains(it, ignoreCase = true) }
    }
}
