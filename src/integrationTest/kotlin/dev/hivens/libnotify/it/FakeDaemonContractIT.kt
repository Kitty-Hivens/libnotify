package dev.hivens.libnotify.it

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment

/**
 * The fake server stands in for every real one, so its strictness is part of the
 * contract: a call a GDBus server would reject must fail the test that made it.
 * These calls come from a raw client, not from the library.
 */
internal class FakeDaemonContractIT : BusTest() {

    private fun rawClient(block: (MemorySegment) -> Unit) {
        val conn = connect(TestBus.address)
        try {
            block(conn)
        } finally {
            disconnect(conn)
        }
    }

    @Test
    fun `a well-formed call passes`() {
        val server = FakeDaemon()
        rawClient { conn ->
            callService(conn, OBJECT_PATH, "CloseNotification") { iter, arena -> appendUint32(iter, arena, 1) } shouldBe true
        }
        server.awaitCloseRequest() shouldBe 1
        server.close()
    }

    @Test
    fun `a call with the wrong signature is rejected and fails the test`() {
        val server = FakeDaemon()
        rawClient { conn ->
            callService(conn, OBJECT_PATH, "CloseNotification") { iter, arena -> appendString(iter, arena, "1") } shouldBe false
        }
        shouldThrow<AssertionError> { server.close() }.message shouldContain "the spec says 'u'"
    }

    @Test
    fun `a call on the wrong object path is rejected and fails the test`() {
        val server = FakeDaemon()
        rawClient { conn ->
            callService(conn, "/wrong/path", "CloseNotification") { iter, arena -> appendUint32(iter, arena, 1) } shouldBe false
        }
        shouldThrow<AssertionError> { server.close() }.message shouldContain "/wrong/path"
    }

    @Test
    fun `an unknown method is rejected and fails the test`() {
        val server = FakeDaemon()
        rawClient { conn ->
            callService(conn, OBJECT_PATH, "MakeCoffee") { _, _ -> } shouldBe false
        }
        shouldThrow<AssertionError> { server.close() }.message shouldContain "MakeCoffee"
    }
}
