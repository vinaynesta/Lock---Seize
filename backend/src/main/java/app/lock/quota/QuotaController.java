package app.lock.quota;

import app.lock.common.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The usage meter in Settings (roadmap 3.2). */
@RestController
public class QuotaController {

    private final QuotaService quotas;

    public QuotaController(QuotaService quotas) {
        this.quotas = quotas;
    }

    @GetMapping("/account/storage")
    public StorageResponse storage(@AuthenticationPrincipal Jwt jwt) {
        QuotaService.Usage u = quotas.usage(CurrentUser.id(jwt));
        return new StorageResponse(u.plan(), u.usedBytes(), u.limitBytes(), u.remainingBytes());
    }

    public record StorageResponse(String plan, long usedBytes, long limitBytes, long remainingBytes) {}
}
