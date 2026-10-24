package app.lock.account;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import app.lock.export.ExportService;
import app.lock.identity.IdentityService;
import app.lock.identity.MfaService;
import app.lock.memory.MemoryService;
import app.lock.outbox.OutboxPublisher;
import app.lock.vault.VaultService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account deletion under Policy C (roadmap 2.5, decisions.md §14). Deliberate and delayed, like the
 * deletion of a single locked memory: it needs the password, and it runs only after a grace period
 * (default 30 days) during which the user can cancel and keeps full use of the account.
 *
 * <p>When it runs, in one transaction:
 * <ul>
 *   <li>personal vaults are purged with everything in them, including locked memories;</li>
 *   <li>in shared vaults the user leaves (the breakup protocol, with a vault-key rotation); their drafts
 *       are discarded, their locked memories and reflections stay, credited to "Former member";</li>
 *   <li>their ZIP exports are deleted;</li>
 *   <li>the user row is anonymised and every session revoked. It is kept, not deleted, because shared
 *       memories and the audit trail still point at it.</li>
 * </ul>
 * Media bytes are deleted asynchronously through the outbox, as for any purge.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountDeletionRepository deletions;
    private final IdentityService identity;
    private final MfaService mfa;
    private final VaultService vaults;
    private final MemoryService memories;
    private final ExportService exports;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final LockProperties props;
    private final Clock clock;

    public AccountService(AccountDeletionRepository deletions, IdentityService identity, MfaService mfa, VaultService vaults,
                          MemoryService memories, ExportService exports, AuditService audit, OutboxPublisher outbox,
                          LockProperties props, Clock clock) {
        this.deletions = deletions;
        this.identity = identity;
        this.mfa = mfa;
        this.vaults = vaults;
        this.memories = memories;
        this.exports = exports;
        this.audit = audit;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public AccountDeletion requestDeletion(UUID userId, String password, String mfaCode) {
        identity.verifyPassword(userId, password);
        mfa.require(userId, mfaCode);
        if (deletions.findByUserIdAndStatus(userId, AccountDeletion.Status.PENDING).isPresent()) {
            throw ApiException.conflict("DELETION_PENDING", "Account deletion is already scheduled");
        }
        Instant now = Instant.now(clock);
        AccountDeletion d = deletions.save(new AccountDeletion(userId, now, now.plus(props.account().deletionGracePeriod())));
        audit.record(AuditEventType.ACCOUNT_DELETION_REQUESTED, userId, null, null,
                Map.of("executeAfter", d.getExecuteAfter().toString()));
        // NotificationEventHandler schedules the reminders at 7 days and 1 day before (roadmap 2.7).
        outbox.publish("user", userId, "account.deletion.requested", Map.of("executeAfter", d.getExecuteAfter().toString()));
        return d;
    }

    @Transactional(readOnly = true)
    public AccountDeletion pending(UUID userId) {
        return deletions.findByUserIdAndStatus(userId, AccountDeletion.Status.PENDING)
                .orElseThrow(() -> ApiException.notFound("Pending account deletion"));
    }

    @Transactional
    public AccountDeletion cancelDeletion(UUID userId) {
        AccountDeletion d = pending(userId);
        d.cancel(Instant.now(clock));
        audit.record(AuditEventType.ACCOUNT_DELETION_CANCELLED, userId, null, null);
        outbox.publish("user", userId, "account.deletion.cancelled", Map.of());
        return d;
    }

    /** Called by the scheduler. Each account is deleted in its own transaction (see {@link AccountDeletionJob}). */
    @Transactional
    public boolean executeNextDue() {
        List<AccountDeletion> due = deletions.lockDue(Instant.now(clock), 1);
        if (due.isEmpty()) {
            return false;
        }
        execute(due.getFirst());
        return true;
    }

    private void execute(AccountDeletion d) {
        UUID userId = d.getUserId();
        List<UUID> personal = vaults.detachDeletedUser(userId);
        int purged = memories.purgeForAccountDeletion(userId, personal);
        personal.forEach(vaults::deletePersonalVault);
        exports.deleteAllFor(userId);
        identity.anonymize(userId);
        d.complete(Instant.now(clock));
        audit.record(AuditEventType.ACCOUNT_DELETED, userId, null, null,
                Map.of("memoriesPurged", purged, "personalVaultsDeleted", personal.size()));
        outbox.publish("user", userId, "account.deleted", Map.of());
        log.info("Deleted account {}: {} memories purged, {} personal vaults removed", userId, purged, personal.size());
    }
}
