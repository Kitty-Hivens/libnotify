package dev.hivens.libnotify

import org.slf4j.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives [NotificationEvent]s from a [Notifier]. A Kotlin lambda or a Java
 * lambda converts to it directly.
 *
 * Runs on the backend's dispatch thread (see [Notifier]), and may call
 * [Notifier.notify], [Notifier.cancel] and [Notifier.close] from there. An
 * exception it throws is logged and does not stop the other listeners.
 */
public fun interface NotificationListener {
    public fun onEvent(event: NotificationEvent)
}

/**
 * One registration made with [Notifier.onEvent]. Closing it stops delivery to that
 * registration only, so a listener registered twice keeps its other registration.
 * Closing it again does nothing. Being [AutoCloseable] without a checked exception,
 * it fits Java's try-with-resources and Kotlin's `use`.
 */
public fun interface Subscription : AutoCloseable {
    override fun close()
}

/** Listener bookkeeping shared by every backend. */
internal class EventListeners(private val log: Logger) {

    /** Removal goes by this object's identity, never by the listener's equality. */
    private class Registration(val listener: NotificationListener)

    private val registrations = CopyOnWriteArrayList<Registration>()

    fun add(listener: NotificationListener): Subscription {
        val registration = Registration(listener)
        registrations.add(registration)
        return Subscription { registrations.remove(registration) }
    }

    fun fire(event: NotificationEvent) {
        for (registration in registrations) {
            try {
                registration.listener.onEvent(event)
            } catch (t: Throwable) {
                log.warn("onEvent listener threw", t)
            }
        }
    }
}
