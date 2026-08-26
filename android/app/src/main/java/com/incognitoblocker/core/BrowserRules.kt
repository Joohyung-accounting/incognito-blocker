package com.incognitoblocker.core

/**
 * Per-browser rules for deciding "is this window in private mode right now?"
 *
 * Android exposes no API that answers that question, so the answer is inferred
 * from what the browser draws on screen.
 *
 * ── Weighting rules (breaking these makes the app unusable) ──────────────
 *
 *   weight 2 — exists ONLY while private mode is active
 *              · private-only view IDs (badge, private tab tray)
 *              · "turn off" style wording, which can only appear when it is on
 *              · the private start page's own copy
 *
 *   weight 1 — merely the NAME of the mode
 *              "Secret mode", "InPrivate", "Private window", "시크릿 모드" …
 *              All of these also appear in the normal-mode menu
 *              ("Turn on Secret mode"), so on their own they must never block.
 *
 * SCORE_THRESHOLD is 2, so two weight-1 signals must coincide to fire.
 *
 * ── Why this much care ──────────────────────────────────────────────────
 * The first version blocked on the bare string "시크릿 모드". The result was
 * that merely opening the menu in Samsung Internet triggered a block. A
 * monitoring tool that cries wolf gets switched off, which makes a false
 * positive worse than a miss.
 *
 * Misses are covered by [com.incognitoblocker.service.NotificationGuardService],
 * which watches the persistent notification browsers post while a private
 * session is open.
 *
 * ── Localised signal values ─────────────────────────────────────────────
 * The Korean strings below are DETECTION DATA, not UI text. They match what a
 * Korean-locale browser renders and must not be translated or moved into
 * strings.xml. To support another language, add its wording here.
 */
object BrowserRules {

    /** Minimum score to block: one conclusive signal, or two circumstantial ones. */
    const val SCORE_THRESHOLD = 2

    enum class Kind {
        /** A node whose getViewIdResourceName() ends with this suffix (most reliable). */
        VIEW_ID_SUFFIX,

        /** Substring of a contentDescription. */
        CONTENT_DESC,

        /** Whole text equals this. */
        TEXT_EXACT,

        /** Text contains this. */
        TEXT_CONTAINS
    }

    data class Signal(
        val kind: Kind,
        val value: String,
        val weight: Int,
        /**
         * Whether to search web page content as well.
         *
         * Default false — only the browser's own UI (toolbar, menu, tab list) is
         * searched. Matching a common phrase like "Secret mode" inside page text
         * would block someone merely reading an article about private browsing.
         *
         * Set true only for very specific copy a browser draws on its own
         * private start page, e.g. "You've gone Incognito". Chrome renders that
         * page as web content, so without this exception opening an incognito
         * tab would not be detected at all.
         */
        val matchPageContent: Boolean = false
    )

    data class Rule(
        val label: String,
        val packages: List<String>,
        val signals: List<Signal>,
        /** The browser is private by nature (Firefox Focus, Tor Browser). */
        val alwaysPrivate: Boolean = false,
        /**
         * Status-bar notification fallback.
         *
         * Use COMPLETE phrases only. A bare word like "private" would also match
         * web-push and download notifications.
         */
        val notificationSignals: List<String> = emptyList()
    )

    // ------------------------------------------------------------------
    // Shared Chromium signals (Chrome / Edge / Brave / Whale / Kiwi / Vivaldi)
    // ------------------------------------------------------------------

    private val chromiumSignals = listOf(
        // --- weight 2: present only in incognito ---------------------------
        // Incognito-only tab tray views. Absent from the normal tab switcher.
        Signal(Kind.VIEW_ID_SUFFIX, "id/incognito_toggle_tabs", 2),
        Signal(Kind.VIEW_ID_SUFFIX, "id/incognito_toggle_tabs_container", 2),
        Signal(Kind.VIEW_ID_SUFFIX, "id/incognito_descriptions", 2),
        // Toolbar incognito badge — one of the few signals that survives after a
        // page has loaded. IDs vary by Chrome milestone; confirm with diagnostic mode.
        Signal(Kind.VIEW_ID_SUFFIX, "id/incognito_badge", 2),
        Signal(Kind.VIEW_ID_SUFFIX, "id/incognito_indicator", 2),

        // Incognito start page copy. Rendered as web content, hence matchPageContent.
        Signal(Kind.TEXT_CONTAINS, "gone Incognito", 2, matchPageContent = true),
        Signal(Kind.TEXT_CONTAINS, "시크릿 모드를 사용", 2, matchPageContent = true),
        Signal(Kind.TEXT_CONTAINS, "시크릿 모드로 전환", 2, matchPageContent = true),

        // "Close"/"exit" wording can only appear while a session is open.
        Signal(Kind.TEXT_CONTAINS, "시크릿 탭 닫기", 2),
        Signal(Kind.TEXT_CONTAINS, "Close Incognito tabs", 2),
        Signal(Kind.CONTENT_DESC, "Incognito tabs", 2),
        Signal(Kind.CONTENT_DESC, "시크릿 탭", 2),

        // --- weight 1: circumstantial, also in the normal menu ---------------
        Signal(Kind.TEXT_EXACT, "Incognito", 1),
        Signal(Kind.TEXT_EXACT, "시크릿", 1)
    )

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    val rules: List<Rule> = listOf(
        Rule(
            label = "Chrome",
            packages = listOf(
                "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary"
            ),
            signals = chromiumSignals,
            notificationSignals = listOf(
                "시크릿 탭 닫기", "Close Incognito tabs", "시크릿 탭이 열려", "Incognito tabs open"
            )
        ),
        Rule(
            label = "Microsoft Edge",
            packages = listOf("com.microsoft.emmx", "com.microsoft.emmx.beta", "com.microsoft.emmx.dev"),
            signals = chromiumSignals + listOf(
                Signal(Kind.TEXT_CONTAINS, "Close InPrivate tabs", 2),
                Signal(Kind.TEXT_CONTAINS, "You're browsing InPrivate", 2, matchPageContent = true),
                Signal(Kind.CONTENT_DESC, "InPrivate tabs", 2),
                Signal(Kind.TEXT_EXACT, "InPrivate", 1)
            ),
            notificationSignals = listOf("Close InPrivate tabs", "InPrivate tabs open")
        ),
        Rule(
            label = "Brave",
            packages = listOf("com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly"),
            signals = chromiumSignals + listOf(
                Signal(Kind.TEXT_CONTAINS, "You're in a private window", 2, matchPageContent = true),
                Signal(Kind.TEXT_CONTAINS, "Close private tabs", 2),
                Signal(Kind.CONTENT_DESC, "Private tabs", 2),
                Signal(Kind.TEXT_EXACT, "Private window", 1),
                Signal(Kind.TEXT_EXACT, "비공개 창", 1)
            ),
            notificationSignals = listOf("Close private tabs", "private tabs open")
        ),
        Rule(
            // Samsung Internet publishes NO policy for disabling secret mode, so
            // screen detection is the only option here. The view IDs are
            // empirical, not vendor-documented — verify with diagnostic mode.
            label = "Samsung Internet",
            packages = listOf("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta"),
            signals = listOf(
                // --- weight 2 ---
                Signal(Kind.VIEW_ID_SUFFIX, "id/tab_bar_secret_badge", 2),
                Signal(Kind.VIEW_ID_SUFFIX, "id/secret_mode_layout", 2),
                Signal(Kind.VIEW_ID_SUFFIX, "id/secret_mode_badge", 2),
                // "Turn off" only appears once secret mode is already on. This is
                // the most trustworthy text signal available for this browser.
                Signal(Kind.TEXT_CONTAINS, "시크릿 모드 끄기", 2),
                Signal(Kind.TEXT_CONTAINS, "Turn off Secret mode", 2),
                Signal(Kind.CONTENT_DESC, "시크릿 모드 끄기", 2),
                Signal(Kind.CONTENT_DESC, "Turn off Secret mode", 2),
                Signal(Kind.TEXT_CONTAINS, "시크릿 모드 사용 중", 2),

                // --- weight 1: also inside the normal menu's "Turn on Secret mode"
                Signal(Kind.TEXT_CONTAINS, "시크릿 모드", 1),
                Signal(Kind.TEXT_CONTAINS, "Secret mode", 1),
                Signal(Kind.CONTENT_DESC, "시크릿 모드", 1),
                Signal(Kind.CONTENT_DESC, "Secret mode", 1)
            ),
            notificationSignals = listOf("시크릿 모드가 켜져", "Secret mode is on")
        ),
        Rule(
            label = "Firefox",
            packages = listOf(
                "org.mozilla.firefox", "org.mozilla.firefox_beta",
                "org.mozilla.fenix", "org.mozilla.fennec_aurora"
            ),
            signals = listOf(
                // --- weight 2 ---
                Signal(Kind.VIEW_ID_SUFFIX, "id/privateBrowsingBadge", 2),
                Signal(Kind.TEXT_CONTAINS, "You're in a private tab", 2, matchPageContent = true),
                Signal(Kind.TEXT_CONTAINS, "사생활 보호 탭입니다", 2, matchPageContent = true),
                Signal(Kind.TEXT_CONTAINS, "Close private tabs", 2),
                Signal(Kind.CONTENT_DESC, "Private tabs", 2),
                Signal(Kind.CONTENT_DESC, "사생활 보호 탭", 2),

                // --- weight 1 ---
                // privateBrowsingButton is the toggle, present on the normal home screen too.
                Signal(Kind.VIEW_ID_SUFFIX, "id/privateBrowsingButton", 1),
                Signal(Kind.TEXT_EXACT, "Private browsing", 1),
                Signal(Kind.TEXT_EXACT, "사생활 보호 모드", 1)
            ),
            notificationSignals = listOf("Close private tabs", "사생활 보호 탭 닫기")
        ),
        Rule(
            label = "Firefox Focus",
            packages = listOf("org.mozilla.focus", "org.mozilla.klar"),
            signals = emptyList(),
            alwaysPrivate = true
        ),
        Rule(
            label = "Tor Browser",
            // org.torproject.android is Orbot, a proxy app, not a browser. Excluded.
            packages = listOf("org.torproject.torbrowser"),
            signals = emptyList(),
            alwaysPrivate = true
        ),
        Rule(
            // NOT always-private: it has ordinary tabs and keeps history. The
            // Fire Button clears data, which is a different thing. Marking it
            // alwaysPrivate would block the whole app permanently.
            label = "DuckDuckGo",
            packages = listOf("com.duckduckgo.mobile.android"),
            signals = listOf(
                Signal(Kind.TEXT_CONTAINS, "Fire Button", 2),
                Signal(Kind.TEXT_CONTAINS, "Close tabs and clear data", 2),
                Signal(Kind.CONTENT_DESC, "Fire Button", 2)
            )
        ),
        Rule(
            label = "Opera",
            packages = listOf(
                "com.opera.browser", "com.opera.browser.beta", "com.opera.gx", "com.opera.mini.native"
            ),
            signals = listOf(
                Signal(Kind.VIEW_ID_SUFFIX, "id/private_mode_badge", 2),
                Signal(Kind.TEXT_CONTAINS, "Close private tabs", 2),
                Signal(Kind.TEXT_CONTAINS, "비공개 탭 닫기", 2),
                Signal(Kind.CONTENT_DESC, "Private tabs", 2),
                Signal(Kind.TEXT_EXACT, "Private mode", 1),
                Signal(Kind.TEXT_EXACT, "비공개 모드", 1)
            )
        ),
        Rule(
            label = "Naver Whale",
            packages = listOf("com.naver.whale"),
            signals = chromiumSignals + listOf(
                Signal(Kind.TEXT_CONTAINS, "시크릿 모드 종료", 2),
                Signal(Kind.TEXT_CONTAINS, "시크릿 모드", 1)
            ),
            notificationSignals = listOf("시크릿 탭 닫기")
        ),
        Rule(
            label = "Vivaldi",
            packages = listOf("com.vivaldi.browser", "com.vivaldi.browser.snapshot"),
            signals = chromiumSignals + listOf(
                Signal(Kind.TEXT_CONTAINS, "Close private tabs", 2),
                Signal(Kind.TEXT_EXACT, "Private window", 1)
            )
        ),
        Rule(
            label = "Kiwi Browser",
            packages = listOf("com.kiwibrowser.browser"),
            signals = chromiumSignals
        ),
        Rule(
            label = "AOSP Browser",
            packages = listOf("com.android.browser"),
            signals = listOf(
                Signal(Kind.TEXT_CONTAINS, "Close incognito tabs", 2),
                Signal(Kind.TEXT_EXACT, "Incognito", 1),
                Signal(Kind.TEXT_EXACT, "시크릿", 1)
            )
        )
    )

    /** Package name to rule, for fast lookup. */
    private val byPackage: Map<String, Rule> = buildMap {
        for (r in rules) for (p in r.packages) put(p, r)
    }

    fun ruleFor(packageName: String?): Rule? = packageName?.let { byPackage[it] }

    /**
     * Every watched package. Must stay in sync with `android:packageNames` in
     * res/xml/accessibility_service_config.xml — that attribute is what actually
     * limits which apps this service can observe.
     */
    val allPackages: List<String> = rules.flatMap { it.packages }.distinct()

    fun isKnownBrowser(packageName: String?): Boolean = ruleFor(packageName) != null
}
