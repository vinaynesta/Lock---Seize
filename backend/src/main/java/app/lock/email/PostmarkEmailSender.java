package app.lock.email;

import app.lock.config.LockProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Sends through Postmark's HTTP API. A non-2xx answer throws, so the outbox retries the send. */
@Component
@ConditionalOnProperty(name = "lock.email.provider", havingValue = "postmark")
public class PostmarkEmailSender implements EmailSender {

    private static final URI ENDPOINT = URI.create("https://api.postmarkapp.com/email");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;
    private final LockProperties.Email props;

    public PostmarkEmailSender(LockProperties props, JsonMapper json) {
        this.props = props.email();
        this.json = json;
        if (this.props.postmarkToken() == null || this.props.postmarkToken().isBlank()) {
            throw new IllegalStateException("lock.email.postmark-token (LOCK_POSTMARK_TOKEN) is required for the postmark provider");
        }
    }

    @Override
    public void send(String to, String subject, String textBody) {
        String body = json.writeValueAsString(Map.of(
                "From", props.from(), "To", to, "Subject", subject, "TextBody", textBody, "MessageStream", "outbound"));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Postmark-Server-Token", props.postmarkToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Postmark's error body names the problem (bad sender, inactive recipient); it holds no secrets.
                throw new IllegalStateException("Postmark answered " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Postmark request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending email", e);
        }
    }
}
