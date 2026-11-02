package app.lock.identity;

import app.lock.config.LockProperties;
import app.lock.email.EmailSender;
import app.lock.outbox.OutboxEvent;
import app.lock.outbox.OutboxEventHandler;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sends the reset link and the "your password was reset" notice (roadmap 2.6). If the token was
 * used or superseded before the event is handled, nothing is sent.
 */
@Component
class PasswordResetMailer implements OutboxEventHandler {

    private final PasswordResetService resets;
    private final UserRepository users;
    private final EmailSender email;
    private final LockProperties props;

    PasswordResetMailer(PasswordResetService resets, UserRepository users, EmailSender email, LockProperties props) {
        this.resets = resets;
        this.users = users;
        this.email = email;
        this.props = props;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.equals(PasswordResetService.RESET_REQUESTED) || eventType.equals(PasswordResetService.RESET_COMPLETED);
    }

    @Override
    public void handle(OutboxEvent event) {
        User user = users.findById(event.getAggregateId()).filter(u -> u.getStatus() == User.Status.ACTIVE).orElse(null);
        if (user == null) {
            return;
        }
        if (event.getEventType().equals(PasswordResetService.RESET_COMPLETED)) {
            email.send(user.getEmail(), "Your Lock password was reset", """
                    Hi %s,

                    The password for your Lock account was just reset, and every device was signed out.

                    If this wasn't you, reset your password again right away. Your memories are safe:
                    a password reset never exposes or changes them.
                    """.formatted(user.getDisplayName()));
            return;
        }
        UUID tokenId = UUID.fromString((String) event.getPayload().get("tokenId"));
        resets.usableToken(tokenId).ifPresent(token -> email.send(user.getEmail(), "Reset your Lock password", """
                Hi %s,

                Someone asked to reset the password for your Lock account. To choose a new one, open:

                %s

                The link works once and expires in %d minutes. If you didn't ask for this, ignore this
                email: your password stays the same.
                """.formatted(user.getDisplayName(), link(token), props.email().resetTtl().toMinutes())));
    }

    private String link(String token) {
        String base = props.email().resetUrl();
        return base + (base.contains("?") ? "&" : "?") + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }
}
