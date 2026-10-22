package com.terminus.agentwebview

/** Result of a low-level driver operation. */
sealed class DriverResult {
    data object Ok : DriverResult()
    data class Failed(val code: ErrorCode, val message: String) : DriverResult()
}

data class SnapshotOptions(
    val maxNodes: Int = 600,
    val maxTextChars: Int = 8_000,
    val maxNameChars: Int = 120,
    /** Skip elements with no layout box. Tests without a layout engine turn this off. */
    val requireLayout: Boolean = true,
)

enum class ImageFormat(val mime: String) { JPEG("image/jpeg"), PNG("image/png") }

data class ScreenshotOptions(
    val maxWidth: Int = 1024,
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 70,
)

class Screenshot(val bytes: ByteArray, val mime: String, val width: Int, val height: Int) {
    override fun toString() = "Screenshot($mime ${width}x$height, ${bytes.size} bytes)"
}

/** Pending JS dialog (alert / confirm / prompt / beforeunload). */
data class DialogInfo(val kind: String, val message: String, val defaultValue: String?) {
    fun toWire(): Map<String, Any?> = mapOf("kind" to kind, "message" to message, "defaultValue" to defaultValue)
}

/** Driver → session callbacks. Called from arbitrary threads. */
interface DriverListener {
    fun onEvent(event: TabEvent)

    /** True between a main-frame load starting and finishing. */
    fun onNavigating(navigating: Boolean)

    /**
     * Asked before the browser loads [url]. Return false to cancel it. [mainFrame] is false
     * for subresources and iframes. The session applies the NavigationPolicy and logs blocks.
     */
    fun allowNavigation(url: String, mainFrame: Boolean): Boolean
}

/**
 * The browser backend. The Android implementation drives a WebView; tests use a
 * scripted fake. Everything stateful about *the agent* (refs, events, handoff,
 * policy) lives above this interface in [AgentSession].
 */
interface BrowserDriver {
    /** Registers the session's listener. Called once, before any command. */
    fun attach(listener: DriverListener)

    /** Last known URL; safe to call from any thread. */
    val currentUrl: String

    suspend fun navigate(url: String): DriverResult
    suspend fun goBack(): DriverResult
    suspend fun goForward(): DriverResult
    suspend fun reload(): DriverResult

    /** Builds a fresh snapshot, stamping refs starting at [startAt]. */
    suspend fun snapshot(startAt: Int, options: SnapshotOptions): PageSnapshot

    /** Executes Click, Fill, Press, Scroll, ScrollTo, Select or Check. Fill text is already secret-substituted. */
    suspend fun perform(action: AgentAction): DriverResult

    suspend fun evaluate(js: String): String?
    suspend fun screenshot(options: ScreenshotOptions): Screenshot

    /** Activity sample for settling; null when the page cannot be evaluated right now. */
    suspend fun samplePage(): PageSample?

    /**
     * Cheap page probe used while a human has control: only the URL and whether
     * each CSS selector currently matches. Must not return page content.
     */
    suspend fun probe(selectors: List<String>): ProbeResult

    /** Answers the open dialog. Returns false if none was open. */
    suspend fun answerDialog(accept: Boolean, text: String?): Boolean

    fun close()
}
