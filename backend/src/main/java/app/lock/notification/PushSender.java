package app.lock.notification;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delivers push messages to device tokens (roadmap 2.7). Implementations: {@link LogPushSender}
 * (development and tests) and {@link ExpoPushSender}.
 */
public interface PushSender {

    record Message(String token, String title, String body, Map<String, Object> data) {}

    /** Tokens the provider says are no longer valid, so their devices can be forgotten. */
    record Result(Set<String> deadTokens) {}

    /** Throws if nothing could be delivered, so the notification is retried. */
    Result send(List<Message> messages);
}
