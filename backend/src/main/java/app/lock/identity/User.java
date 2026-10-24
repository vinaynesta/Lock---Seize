package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    /** DELETED: the account was deleted (roadmap 2.5) and the row anonymised; it is kept for attribution. */
    public enum Status { ACTIVE, DISABLED, DELETED }

    public static final String FORMER_MEMBER = "Former member";

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Storage plan (roadmap 3.2); null means the configured default. */
    private String plan;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {}

    User(String email, String passwordHash, String displayName, Instant now) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Set by billing or an operator; there is no self-service endpoint. */
    public void changePlan(String plan, Instant now) {
        this.plan = plan;
        this.updatedAt = now;
    }

    void changePasswordHash(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.updatedAt = now;
    }

    /**
     * Policy C: the row stays so shared memories and the audit trail keep a valid author, but nothing
     * personal remains. The email is replaced so it can sign up again; the password can never match.
     */
    void anonymize(String unusablePasswordHash, Instant now) {
        this.email = "deleted-" + id + "@deleted.invalid";
        this.displayName = FORMER_MEMBER;
        this.passwordHash = unusablePasswordHash;
        this.status = Status.DELETED;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Status getStatus() { return status; }
    public String getPlan() { return plan; }
    public Instant getCreatedAt() { return createdAt; }
}
