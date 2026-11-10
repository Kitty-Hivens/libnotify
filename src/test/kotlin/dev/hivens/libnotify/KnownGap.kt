package dev.hivens.libnotify

import io.kotest.assertions.fail

/**
 * A contract the library does not meet yet. [body] sets the scene with ordinary
 * assertions and states the unmet contract inside exactly one [GapScope.gap]
 * block. The test passes while that block fails, and fails once it passes, so a
 * fix cannot leave the entry behind. A failure outside the block is a real one.
 *
 * The integration suite has its own copy, with tests of its own in
 * KnownGapContractTest, since the two source sets do not share code.
 */
internal fun knownGap(reason: String, body: GapScope.() -> Unit) {
    val scope = GapScope()
    try {
        scope.body()
    } catch (open: GapScope.StillOpen) {
        System.err.println("known gap: $reason (${open.cause?.message})")
        return
    }
    if (!scope.checked) fail("knownGap '$reason' never reached its gap { } block")
    fail("This gap is closed, turn it into a plain test: $reason")
}

internal class GapScope {
    internal class StillOpen(cause: AssertionError) : RuntimeException(cause)

    internal var checked = false
        private set

    /** The contract that is not met yet. Must be the last thing [knownGap]'s body does. */
    fun gap(assertion: () -> Unit) {
        check(!checked) { "a knownGap has exactly one gap { } block" }
        checked = true
        try {
            assertion()
        } catch (failed: AssertionError) {
            throw StillOpen(failed)
        }
    }
}
