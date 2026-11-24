package com.exciteddev.flironepro.testing

import java.util.concurrent.atomic.AtomicLong

/** Milliseconds from a monotonic clock; the threads of the code under test need real time to tell a timeout from an error. */
val realClock: () -> Long = { System.nanoTime() / NANOS_PER_MS }

private const val NANOS_PER_MS = 1_000_000L
private const val POLL_MS = 5L
private const val DEFAULT_WAIT_MS = 5000L

/** A clock that only moves when the test says so. */
class FakeClock {
    private val time = AtomicLong(0)
    val now: () -> Long = { time.get() }

    fun advance(ms: Long) {
        time.addAndGet(ms)
    }
}

/** Waits (up to [timeoutMs]) until [condition] holds; fails the test with [what] if it never does. */
fun awaitThat(what: String, timeoutMs: Long = DEFAULT_WAIT_MS, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for: $what")
        Thread.sleep(POLL_MS)
    }
}
