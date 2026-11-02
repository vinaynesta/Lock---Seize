package app.lock.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development sender: logs titles only and keeps what was sent, for tests. Nothing leaves the machine. */
@Component
@ConditionalOnProperty(name = "lock.push.provider", havingValue = "log", matchIfMissing = true)
public class LogPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LogPushSender.class);

    private final List<Message> sent = new CopyOnWriteArrayList<>();

    @Override
    public Result send(List<Message> messages) {
        messages.forEach(m -> log.info("push title=\"{}\" devices=1", m.title()));
        sent.addAll(messages);
        return new Result(Set.of());
    }

    /** Test hook. */
    public List<Message> sent() {
        return new ArrayList<>(sent);
    }

    /** Test hook. */
    public void clear() {
        sent.clear();
    }
}
