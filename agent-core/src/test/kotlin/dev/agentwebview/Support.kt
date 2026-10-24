package dev.agentwebview

import java.util.concurrent.CopyOnWriteArrayList

class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMs() = now
}

/** Manual-time scheduler: nothing runs until [advance]. */
class FakeScheduler(private val clock: FakeClock) : Scheduler {
    private class Task(var due: Long, val period: Long, val run: Runnable, var cancelled: Boolean = false)

    private val tasks = CopyOnWriteArrayList<Task>()

    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        val t = Task(clock.now + delayMs, 0, task)
        tasks += t
        return Cancellable { t.cancelled = true; tasks.remove(t) }
    }

    override fun scheduleRepeating(periodMs: Long, task: Runnable): Cancellable {
        val t = Task(clock.now + periodMs, periodMs, task)
        tasks += t
        return Cancellable { t.cancelled = true; tasks.remove(t) }
    }

    fun advance(ms: Long) {
        val end = clock.now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
            clock.now = maxOf(clock.now, next.due)
            if (next.period > 0) next.due += next.period else tasks.remove(next)
            next.run.run()
        }
        clock.now = end
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}

/** Scripted browser. Tests poke fields and call the emit helpers. */
class FakeDriver : BrowserDriver {
    lateinit var listener: DriverListener
    @Volatile override var currentUrl: String = "about:blank"
    val performed = CopyOnWriteArrayList<AgentAction>()
    val evaluated = CopyOnWriteArrayList<String>()
    var snapshotNodes: List<SnapNode> = emptyList()
    var snapshotFrames: List<FrameInfo> = emptyList()
    var title = "Fake"
    var performResult: (AgentAction) -> DriverResult = { DriverResult.Ok }
    var onPerform: (AgentAction) -> Unit = {}
    var sample: PageSample? = PageSample("complete", 0, 10_000, "")
    var evalResult: String? = "42"
    var dialogOpen = false
    var closed = false
    var snapshotCalls = 0
    var probeCalls = 0
    var present: Set<String> = emptySet()
    var screenshotCalls = 0
    var frameOrigin: String? = null
    private var counter = 1

    override fun attach(listener: DriverListener) { this.listener = listener }

    fun load(url: String): Boolean {
        if (!listener.allowNavigation(url, true)) return false
        listener.onNavigating(true)
        listener.onEvent(TabEvent.PageStarted(url))
        currentUrl = url
        listener.onNavigating(false)
        listener.onEvent(TabEvent.PageFinished(url, title))
        return true
    }

    override suspend fun navigate(url: String): DriverResult =
        if (load(url)) DriverResult.Ok else DriverResult.Failed(ErrorCode.NAV_BLOCKED, "blocked by listener")

    override suspend fun goBack() = DriverResult.Ok
    override suspend fun goForward() = DriverResult.Ok
    override suspend fun reload(): DriverResult { load(currentUrl); return DriverResult.Ok }

    override suspend fun snapshot(startAt: Int, options: SnapshotOptions): PageSnapshot {
        snapshotCalls++
        val next = counter
        // Re-number refs from the session's counter like the real JS does.
        var n = maxOf(startAt, counter)
        fun renum(node: SnapNode): SnapNode {
            val ref = if (node.isInteractive) "@e${n++}" else ""
            return node.copy(ref = ref, children = node.children.map(::renum))
        }
        val nodes = snapshotNodes.map(::renum)
        counter = n
        return PageSnapshot(currentUrl, title, nodes, frames = snapshotFrames, nextIndex = n)
    }

    override suspend fun perform(action: AgentAction): DriverResult {
        performed += action
        onPerform(action)
        return performResult(action)
    }

    override suspend fun evaluate(js: String): String? { evaluated += js; return evalResult }
    override suspend fun screenshot(options: ScreenshotOptions): Screenshot {
        screenshotCalls++
        return Screenshot(byteArrayOf(1, 2, 3), options.format.mime, 10, 10)
    }

    override suspend fun samplePage(): PageSample? = sample?.copy(url = currentUrl)
    override suspend fun probe(selectors: List<String>): ProbeResult {
        probeCalls++
        return ProbeResult(currentUrl, selectors.filter { it in present }.toSet())
    }

    override suspend fun originOf(ref: String): String? = frameOrigin

    override suspend fun answerDialog(accept: Boolean, text: String?): Boolean {
        if (!dialogOpen) return false
        dialogOpen = false
        return true
    }

    override fun close() { closed = true }

    fun openDialog(kind: String, message: String) {
        dialogOpen = true
        listener.onEvent(TabEvent.DialogOpened(kind, message, null))
    }
}

fun btn(name: String, ref: String = "@e1") = SnapNode("button", name, ref)
fun leaf(role: String, name: String, ref: String = "") = SnapNode(role, name, ref)
