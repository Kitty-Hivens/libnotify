package dev.hivens.libnotify.it

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A private dbus-daemon for one test worker.
 *
 * The configuration lists no service directories, so nothing can be activated on
 * it: no real notification daemon starts and shows test banners on the desktop.
 * It refuses to run on any address but the one the Gradle task generated, which
 * stops a run from an IDE from posting to the session bus it inherited.
 */
internal object TestBus {
    private const val ADDRESS_PREFIX = "unix:abstract=libnotify-it-"

    private var process: Process? = null

    val address: String by lazy { start() }

    /** Kill the bus under everyone connected to it. A class that calls this has its worker to itself. */
    fun kill() {
        val running = process ?: return
        running.destroyForcibly()
        running.waitFor(5, TimeUnit.SECONDS)
    }

    /** Bring the bus back on the same address, the way a restarted session bus comes back. */
    fun ensureRunning() {
        address
        if (process?.isAlive != true) launch(address)
    }

    private fun start(): String {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS").orEmpty()
        check(address.startsWith(ADDRESS_PREFIX)) {
            "Integration tests post real notifications to the session bus, so they only run on " +
                "the private bus from `./gradlew integrationTest`. Inherited address: '$address'"
        }
        launch(address)
        return address
    }

    private fun launch(address: String) {
        val config = Files.createTempFile("libnotify-it-", ".conf")
        Files.writeString(
            config,
            """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
              "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>$address</listen>
              <auth>EXTERNAL</auth>
              <policy context="default">
                <allow send_destination="*" eavesdrop="true"/>
                <allow eavesdrop="true"/>
                <allow own="*"/>
              </policy>
            </busconfig>
            """.trimIndent(),
        )
        val daemon = ProcessBuilder(
            "dbus-daemon",
            "--nofork",
            "--nopidfile",
            "--print-address",
            "--config-file=$config",
        ).redirectErrorStream(true).start()
        Runtime.getRuntime().addShutdownHook(Thread { daemon.destroyForcibly() })
        // The daemon prints its address once it is listening.
        val printed = daemon.inputStream.bufferedReader().readLine()
        check(printed != null && printed.startsWith("unix:")) { "dbus-daemon did not start: $printed" }
        Files.deleteIfExists(config)
        process = daemon
    }
}
