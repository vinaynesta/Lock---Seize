package app.lock.email;

import app.lock.config.LockProperties;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development sender: nothing leaves the machine. Logs the recipient and subject; the body (which
 * holds reset links) only when {@code lock.email.log-body} is on, as in the local profile. Keeps the
 * last few messages in memory so tests can read them.
 */
@Component
@ConditionalOnProperty(name = "lock.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);
    private static final int KEEP = 50;

    public record Message(String to, String subject, String body) {}

    private final Deque<Message> sent = new ArrayDeque<>();
    private final boolean logBody;

    public LogEmailSender(LockProperties props) {
        this.logBody = props.email() != null && props.email().logBody();
    }

    @Override
    public synchronized void send(String to, String subject, String textBody) {
        if (sent.size() >= KEEP) {
            sent.removeFirst();
        }
        sent.addLast(new Message(to, subject, textBody));
        if (logBody) {
            log.info("email to={} subject=\"{}\"\n{}", to, subject, textBody);
        } else {
            log.info("email to={} subject=\"{}\" (body not logged)", to, subject);
        }
    }

    /** Test hook: messages sent so far, oldest first. */
    public synchronized List<Message> sent() {
        return new ArrayList<>(sent);
    }
}
