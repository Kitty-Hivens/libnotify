package dev.hivens.libnotify

import io.kotest.assertions.fail

/**
 * The contract [body] states is not met yet. The test passes while the assertion
 * fails, and fails as soon as it stops failing, so a fix cannot leave the entry
 * behind. Anything but a failed assertion is a real failure. The integration
 * suite has its own copy, since the two source sets do not share code.
 */
internal fun knownGap(reason: String, body: () -> Unit) {
    try {
        body()
    } catch (expected: AssertionError) {
        System.err.println("known gap: $reason (${expected.message})")
        return
    }
    fail("This gap is closed, turn it into a plain test: $reason")
}
