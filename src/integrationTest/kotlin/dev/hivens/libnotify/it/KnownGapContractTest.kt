package dev.hivens.libnotify.it

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** knownGap decides whether a gap counts as open, so it gets tests of its own. No bus needed. */
internal class KnownGapContractTest {

    @Test
    fun `a failing gap assertion means the gap is still open and the test passes`() {
        knownGap("demo") { gap { 1 shouldBe 2 } }
    }

    @Test
    fun `a passing gap assertion fails the test so the entry gets moved`() {
        shouldThrow<AssertionError> { knownGap("demo") { gap { 1 shouldBe 1 } } }
            .message shouldContain "gap is closed"
    }

    @Test
    fun `a failed assertion before the gap block is a real failure`() {
        val failure = shouldThrow<AssertionError> {
            knownGap("demo") {
                "setup" shouldBe "broken"
                gap { 1 shouldBe 2 }
            }
        }
        failure.message shouldContain "broken"
    }

    @Test
    fun `an exception before the gap block is a real failure`() {
        shouldThrow<IllegalStateException> {
            knownGap("demo") {
                error("the setup could not post")
            }
        }
    }

    @Test
    fun `an entry without a gap block fails`() {
        shouldThrow<AssertionError> { knownGap("demo") { } }
            .message shouldContain "never reached"
    }

    @Test
    fun `an entry with two gap blocks fails`() {
        shouldThrow<IllegalStateException> {
            knownGap("demo") {
                gap { 1 shouldBe 1 }
                gap { 1 shouldBe 2 }
            }
        }
    }
}
