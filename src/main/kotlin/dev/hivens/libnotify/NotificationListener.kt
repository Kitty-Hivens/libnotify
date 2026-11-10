package dev.hivens.libnotify

import org.slf4j.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives [NotificationEvent]s from a [Notifier]. A Kotlin lambda or a Java
 * lambda converts to it directly.
 *
 * Runs on the notifier's own event thread, one event at a time and in order
 * (see [Notifier]), and may call [Notifier.notify], [Notifier.cancel] and
 * [Notifier.close] from there. An exception it throws is logged and does not
 * stop the other listeners.
 *
 * Keep it short. While it runs, no other event of that notifier is delivered,
 * and a call another thread makes to the same notifier waits, giving up unsent
 * after about a second.
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

/**
 * Listener bookkeeping shared by every backend. A backend calls [fire] only on its
 * one event thread, which is what makes the queue below safe without a lock.
 */
internal class EventListeners(private val log: Logger) {

    /** Removal goes by this object's identity, never by the listener's equality. */
    private class Registration(val listener: NotificationListener)

    private val registrations = CopyOnWriteArrayList<Registration>()

    // Event thread only.
    private val pending = ArrayDeque<NotificationEvent>()
    private var delivering = false

    fun add(listener: NotificationListener): Subscription {
        val registration = Registration(listener)
        registrations.add(registration)
        return Subscription { registrations.remove(registration) }
    }

    /**
     * Deliver [event] to every listener. An event raised while one is being
     * delivered, by a listener calling cancel for one, waits until every listener
     * has had the current one, so no listener is ever entered twice at once.
     */
    fun fire(event: NotificationEvent) {
        pending.addLast(event)
        if (delivering) return
        delivering = true
        try {
            while (true) {
                val next = pending.removeFirstOrNull() ?: break
                deliver(next)
            }
        } finally {
            delivering = false
        }
    }

    private fun deliver(event: NotificationEvent) {
        for (registration in registrations) {
            try {
                registration.listener.onEvent(event)
            } catch (t: Throwable) {
                log.warn("onEvent listener threw", t)
            }
        }
    }
}
