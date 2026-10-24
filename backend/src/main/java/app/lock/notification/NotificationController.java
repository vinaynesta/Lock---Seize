package app.lock.notification;

import app.lock.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Push notifications (roadmap 2.7): this device's push token, and which notifications the user gets. */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** Idempotent. Registering a token that belonged to another account moves it to this one. */
    @PostMapping("/devices")
    public DeviceResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRequest req) {
        return DeviceResponse.of(notifications.registerDevice(CurrentUser.id(jwt), req.pushToken(), req.platform()));
    }

    @GetMapping("/devices")
    public List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return notifications.devicesOf(CurrentUser.id(jwt)).stream().map(DeviceResponse::of).toList();
    }

    /** Called on sign-out, before the session is revoked. The token is in the body: it is not a URL-safe id. */
    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UnregisterRequest req) {
        notifications.unregisterDevice(CurrentUser.id(jwt), req.pushToken());
    }

    @GetMapping("/notification-preferences")
    public Map<NotificationCategory, Boolean> preferences(@AuthenticationPrincipal Jwt jwt) {
        return notifications.preferences(CurrentUser.id(jwt));
    }

    /** Partial update, e.g. {@code {"ON_THIS_DAY": false}}. DELETION_REMINDER cannot be turned off. */
    @PatchMapping("/notification-preferences")
    public Map<NotificationCategory, Boolean> updatePreferences(@AuthenticationPrincipal Jwt jwt,
                                                                @RequestBody Map<NotificationCategory, Boolean> changes) {
        return notifications.updatePreferences(CurrentUser.id(jwt), changes);
    }

    public record DeviceRequest(
            @NotBlank @Size(max = 300) String pushToken,
            @NotNull Device.Platform platform) {}

    public record UnregisterRequest(@NotBlank @Size(max = 300) String pushToken) {}

    public record DeviceResponse(UUID id, Device.Platform platform, Instant createdAt, Instant lastSeenAt) {
        static DeviceResponse of(Device d) {
            return new DeviceResponse(d.getId(), d.getPlatform(), d.getCreatedAt(), d.getLastSeenAt());
        }
    }
}
