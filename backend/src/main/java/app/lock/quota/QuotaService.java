package app.lock.quota;

import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import app.lock.identity.IdentityService;
import app.lock.identity.User;
import app.lock.memory.MemoryAssetRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/**
 * Storage quotas per plan (roadmap 3.2). Enforced when an upload is initiated (presign time), using
 * the size the client declares; the upload is later verified against that exact size, so it cannot
 * grow afterwards. In-flight uploads count, so many parallel presigns cannot overshoot the quota.
 *
 * <p>Going over quota never touches what is already preserved: locked memories stay, nothing is
 * deleted; only new uploads are refused.
 */
@Service
public class QuotaService {

    private final MemoryAssetRepository assets;
    private final IdentityService identity;
    private final LockProperties props;

    public QuotaService(MemoryAssetRepository assets, IdentityService identity, LockProperties props) {
        this.assets = assets;
        this.identity = identity;
        this.props = props;
    }

    public record Usage(String plan, long usedBytes, long limitBytes) {
        public long remainingBytes() {
            return Math.max(0, limitBytes - usedBytes);
        }
    }

    @Transactional(readOnly = true)
    public Usage usage(UUID userId) {
        String plan = planOf(identity.get(userId));
        return new Usage(plan, assets.bytesUsedBy(userId), limitOf(plan));
    }

    /** Throws 413 QUOTA_EXCEEDED if {@code additionalBytes} would take the user over their plan. */
    @Transactional(readOnly = true)
    public void requireRoomFor(UUID userId, long additionalBytes) {
        Usage u = usage(userId);
        if (additionalBytes > u.remainingBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "QUOTA_EXCEEDED",
                    "This file needs %s but only %s of your %s storage is left".formatted(
                            human(additionalBytes), human(u.remainingBytes()), human(u.limitBytes())));
        }
    }

    private String planOf(User user) {
        String plan = user.getPlan() == null ? props.quota().defaultPlan() : user.getPlan();
        return props.quota().plans().containsKey(plan) ? plan : props.quota().defaultPlan();
    }

    private long limitOf(String plan) {
        DataSize size = props.quota().plans().get(plan);
        if (size == null) {
            throw new IllegalStateException("lock.quota.plans has no entry for the default plan '" + plan + "'");
        }
        return size.toBytes();
    }

    static String human(long bytes) {
        if (bytes >= 1L << 30) {
            return "%.1f GB".formatted(bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return "%.1f MB".formatted(bytes / (double) (1L << 20));
        }
        return "%d KB".formatted(Math.max(bytes / 1024, bytes > 0 ? 1 : 0));
    }
}
