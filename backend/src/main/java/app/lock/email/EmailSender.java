package app.lock.email;

/**
 * Outgoing email (roadmap 2.6). Implementations: {@link LogEmailSender} (development and tests) and
 * {@link PostmarkEmailSender}. Called from outbox handlers only, so a slow provider never holds a
 * request or a transaction, and a failed send is retried.
 */
public interface EmailSender {

    void send(String to, String subject, String textBody);
}
