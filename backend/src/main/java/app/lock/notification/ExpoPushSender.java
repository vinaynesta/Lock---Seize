package app.lock.notification;

import app.lock.config.LockProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Expo push service (https://docs.expo.dev/push-notifications/sending-notifications/). Sends in
 * chunks of 100. Per-message {@code DeviceNotRegistered} errors mark the token dead; any other
 * failure of a whole request throws so the notification is retried.
 */
@Component
@ConditionalOnProperty(name = "lock.push.provider", havingValue = "expo")
public class ExpoPushSender implements PushSender {

    private static final URI ENDPOINT = URI.create("https://exp.host/--/api/v2/push/send");
    private static final int CHUNK = 100;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;
    private final String accessToken;

    public ExpoPushSender(LockProperties props, JsonMapper json) {
        this.json = json;
        this.accessToken = props.push().expoAccessToken();
    }

    @Override
    public Result send(List<Message> messages) {
        Set<String> dead = new HashSet<>();
        for (int i = 0; i < messages.size(); i += CHUNK) {
            List<Message> chunk = messages.subList(i, Math.min(i + CHUNK, messages.size()));
            List<Map<String, Object>> payload = new ArrayList<>();
            for (Message m : chunk) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("to", m.token());
                p.put("title", m.title());
                p.put("body", m.body());
                p.put("data", m.data());
                p.put("sound", "default");
                payload.add(p);
            }
            JsonNode tickets = post(json.writeValueAsString(payload)).path("data");
            for (int j = 0; j < chunk.size() && j < tickets.size(); j++) {
                JsonNode ticket = tickets.get(j);
                if ("error".equals(ticket.path("status").asString())
                        && "DeviceNotRegistered".equals(ticket.path("details").path("error").asString())) {
                    dead.add(chunk.get(j).token());
                }
            }
        }
        return new Result(dead);
    }

    private JsonNode post(String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accessToken != null && !accessToken.isBlank()) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Expo push answered " + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Expo push request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending push", e);
        }
    }
}
