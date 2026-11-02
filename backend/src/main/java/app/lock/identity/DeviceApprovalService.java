package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.crypto.E2eFormats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device-to-device approval for Sealed Vault keys (roadmap 3.6e, decisions §24 option B):
 * <ol>
 *   <li>The new device (signed in, keys locked) makes a one-time X25519 key pair and posts the public key.
 *       It shows a QR code with the request id and the key's fingerprint.</li>
 *   <li>An unlocked device of the same account scans it, checks the fingerprint matches the request the
 *       server lists, and posts the account secret key sealed to that one-time key.</li>
 *   <li>The new device collects it once; the row is deleted on collection.</li>
 * </ol>
 * The QR check is what stops a server (or anyone with a stolen token) from inserting its own request
 * key: the approving device compares the fingerprint it scanned with the one the server returns.
 */
@Service
public class DeviceApprovalService {

    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_LIVE = 5;
    /** 0x01 || ephemeral public key (32) || nonce (12) || 32-byte key + tag (16). */
    static final int SEALED_SECRET_BYTES = E2eFormats.WRAPPED_VAULT_KEY_BYTES;

    private final DeviceApprovalRepository approvals;
    private final KeyBundleRepository bundles;
    private final AuditService audit;
    private final Clock clock;

    DeviceApprovalService(DeviceApprovalRepository approvals, KeyBundleRepository bundles, AuditService audit, Clock clock) {
        this.approvals = approvals;
        this.bundles = bundles;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DeviceApproval request(UUID userId, String requestKey, String deviceName) {
        if (!bundles.existsById(userId)) {
            throw ApiException.conflict("KEY_BUNDLE_REQUIRED", "This account has no Sealed Vault keys to approve");
        }
        Instant now = Instant.now(clock);
        if (approvals.countLive(userId, now) >= MAX_LIVE) {
            throw ApiException.conflict("TOO_MANY_REQUESTS", "Too many pending device approvals; wait a few minutes");
        }
        String name = deviceName == null ? null : deviceName.strip();
        return approvals.save(new DeviceApproval(userId, E2eFormats.publicKey(requestKey),
                name == null || name.isEmpty() ? null : name.substring(0, Math.min(100, name.length())), now, now.plus(TTL)));
    }

    @Transactional(readOnly = true)
    public List<DeviceApproval> pending(UUID userId) {
        return approvals.findPending(userId, Instant.now(clock));
    }

    @Transactional
    public DeviceApproval approve(UUID userId, UUID approvalId, String sealedSecret) {
        DeviceApproval a = own(userId, approvalId);
        if (a.getApprovedAt() != null) {
            throw ApiException.conflict("ALREADY_APPROVED", "This device was already approved");
        }
        byte[] sealed = E2eFormats.decode(sealedSecret, "sealedSecret");
        if (sealed.length != SEALED_SECRET_BYTES || sealed[0] != E2eFormats.FORMAT_V1) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "sealedSecret must be a version-1 sealed key of " + SEALED_SECRET_BYTES + " bytes");
        }
        a.approve(sealed, Instant.now(clock));
        audit.record(AuditEventType.DEVICE_APPROVED, userId, null, null,
                Map.of("approvalId", approvalId.toString(), "device", a.getDeviceName() == null ? "" : a.getDeviceName()));
        return a;
    }

    /** One-time pickup by the new device. Empty while not approved yet; the row is gone afterwards. */
    @Transactional
    public java.util.Optional<byte[]> collect(UUID userId, UUID approvalId) {
        DeviceApproval a = own(userId, approvalId);
        if (a.getSealedSecret() == null) {
            return java.util.Optional.empty();
        }
        byte[] sealed = a.getSealedSecret();
        approvals.delete(a);
        return java.util.Optional.of(sealed);
    }

    @Transactional
    public void cancel(UUID userId, UUID approvalId) {
        approvals.delete(own(userId, approvalId));
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT3M")
    @Transactional
    public int deleteExpired() {
        return approvals.deleteExpired(Instant.now(clock));
    }

    private DeviceApproval own(UUID userId, UUID approvalId) {
        DeviceApproval a = approvals.findById(approvalId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Device approval"));
        if (a.isExpired(Instant.now(clock))) {
            throw ApiException.conflict("APPROVAL_EXPIRED", "This approval request expired; start again on the new device");
        }
        return a;
    }
}
