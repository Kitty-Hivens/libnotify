package dev.hivens.libnotify.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hivens.libnotify.DismissReason;
import dev.hivens.libnotify.Notification;
import dev.hivens.libnotify.NotificationAction;
import dev.hivens.libnotify.NotificationEvent;
import dev.hivens.libnotify.NotificationHandle;
import dev.hivens.libnotify.Notifier;
import dev.hivens.libnotify.NotifierConfig;
import dev.hivens.libnotify.Subscription;
import dev.hivens.libnotify.Timeout;
import dev.hivens.libnotify.Urgency;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The API the way a Java caller writes it. Compiling this file is half the test:
 * no Companion, no Kotlin function types, no checked exception from close.
 */
class JavaApiIT extends BusTest {

    @Test
    void postsListensAndCancelsFromJava() throws InterruptedException {
        FakeDaemon server = daemon(FakeDaemon.Companion.getFULL_CAPABILITIES(), 1);
        NotifierConfig config = new NotifierConfig.Builder("Java App").appId("com.example.JavaApp").build();

        try (Notifier notifier = Notifier.create(config)) {
            assertNotNull(notifier);
            BlockingQueue<NotificationEvent> events = new LinkedBlockingQueue<>();

            try (Subscription subscription = notifier.onEvent(events::add)) {
                Notification notification = new Notification.Builder("From Java")
                        .body("Body")
                        .urgency(Urgency.CRITICAL)
                        .timeout(new Timeout.After(4_000))
                        .actions(List.of(new NotificationAction("open", "Open")))
                        .tag("java")
                        .build();
                NotificationHandle handle = notifier.notify(notification);
                assertNotNull(handle);
                assertEquals("java", handle.getId());

                ReceivedNotify got = server.awaitNotify(5_000);
                assertEquals("Java App", got.getAppName());
                assertEquals("From Java", got.getSummary());
                assertEquals(4_000, got.getExpireTimeout());
                assertEquals("com.example.JavaApp", got.getHints().get("desktop-entry"));

                server.click(got.getId(), "open");
                NotificationEvent clicked = events.poll(5, TimeUnit.SECONDS);
                NotificationEvent.ActionInvoked invoked = assertInstanceOf(NotificationEvent.ActionInvoked.class, clicked);
                assertEquals("java", invoked.getNotificationId());
                assertEquals("open", invoked.getActionId());

                assertTrue(notifier.cancel(handle));
                NotificationEvent closed = events.poll(5, TimeUnit.SECONDS);
                NotificationEvent.Dismissed dismissed = assertInstanceOf(NotificationEvent.Dismissed.class, closed);
                assertEquals(DismissReason.CLOSED, dismissed.getReason());

                Notification changed = notification.toBuilder().title("Changed in Java").build();
                assertEquals("Changed in Java", changed.getTitle());
                assertEquals(notification.getTag(), changed.getTag());
            }

            // The subscription is closed: nothing arrives any more.
            notifier.notify(new Notification.Builder("after").tag("after").build());
            server.click(server.awaitNotify(5_000).getId(), "default");
            assertNull(events.poll(400, TimeUnit.MILLISECONDS));

            assertTrue(notifier.getCapabilities().getActions());
        }
    }
}
