package dev.agentwebview

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Time source. Injectable so state machines are testable without sleeping. */
interface Clock {
    fun nowMs(): Long
}

object WallClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

fun interface Cancellable {
    fun cancel()
}

/** Timer facility used for heartbeats, handoff timeouts and condition polling. */
interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): Cancellable
    fun scheduleRepeating(periodMs: Long, task: Runnable): Cancellable
}

/** Scheduler backed by a single daemon thread. Tasks must not block. */
class ExecutorScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "agent-webview-scheduler").also { it.isDaemon = true }
    },
) : Scheduler, Closeable {
    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        val f: ScheduledFuture<*> = executor.schedule(guard(task), delayMs, TimeUnit.MILLISECONDS)
        return Cancellable { f.cancel(false) }
    }

    override fun scheduleRepeating(periodMs: Long, task: Runnable): Cancellable {
        val f: ScheduledFuture<*> =
            executor.scheduleWithFixedDelay(guard(task), periodMs, periodMs, TimeUnit.MILLISECONDS)
        return Cancellable { f.cancel(false) }
    }

    // A throwing task must not cancel a repeating schedule.
    private fun guard(task: Runnable) = Runnable { try { task.run() } catch (_: Throwable) { } }

    override fun close() {
        executor.shutdownNow()
    }
}
