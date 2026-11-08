package app.lock.memory;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import app.lock.crypto.ContentCipher;
import app.lock.identity.IdentityService;
import app.lock.identity.MfaService;
import app.lock.search.SearchIndex;
import app.lock.media.storage.ObjectStorage;
import app.lock.outbox.OutboxPublisher;
import app.lock.vault.VaultAccess;
import app.lock.vault.VaultService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the most important domain rule: LOCKED memory content cannot be mutated through normal
 * operations (design doc §8.5). The database triggers in V1 enforce the same rule independently.
 */
@Service
public class MemoryService {

    static final String TITLE = "title";
    static final String THOUGHT = "thought";
    static final String FEELING = "feeling";
    static final String LOCATION = "location";
    static final String COORDINATES = "coordinates";
    static final String REFLECTION = "reflection";

    private static final Duration SAME_PERIOD_WINDOW = Duration.ofDays(30);

    private final MemoryRepository memories;
    private final MemoryAssetRepository assets;
    private final AssetDerivativeRepository derivatives;
    private final ReflectionRepository reflections;
    private final DeletionRequestRepository deletions;
    private final VaultService vaults;
    private final IdentityService identity;
    private final MfaService mfa;
    private final SearchIndex searchIndex;
    private final app.lock.vault.SealedVaultKeyService sealedKeys;
    private final ContentCipher cipher;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final EntityManager em;
    private final MemoryMetrics metrics;
    private final LockProperties props;
    private final Clock clock;

    public MemoryService(MemoryRepository memories, MemoryAssetRepository assets, AssetDerivativeRepository derivatives,
                         ReflectionRepository reflections,
                         DeletionRequestRepository deletions, VaultService vaults, IdentityService identity, MfaService mfa, SearchIndex searchIndex,
                         app.lock.vault.SealedVaultKeyService sealedKeys,
                         ContentCipher cipher, ObjectStorage storage, AuditService audit, OutboxPublisher outbox,
                         EntityManager em, MemoryMetrics metrics, LockProperties props, Clock clock) {
        this.memories = memories;
        this.assets = assets;
        this.derivatives = derivatives;
        this.reflections = reflections;
        this.deletions = deletions;
        this.vaults = vaults;
        this.identity = identity;
        this.mfa = mfa;
        this.searchIndex = searchIndex;
        this.sealedKeys = sealedKeys;
        this.cipher = cipher;
        this.storage = storage;
        this.audit = audit;
        this.outbox = outbox;
        this.em = em;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    /**
     * What a client sends. Server-side vaults use the plaintext fields; a sealed vault (roadmap 3.6)
     * sends {@code sealed} instead, encrypted on the device, and the plaintext fields must be null.
     */
    public record Content(String title, String thought, String feeling, String location, Instant occurredAt, Instant revealAt,
                          SealedContent sealed, MemoryView.Coordinates coordinates) {

        public Content(String title, String thought, String feeling, String location, Instant occurredAt, Instant revealAt,
                       SealedContent sealed) {
            this(title, thought, feeling, location, occurredAt, revealAt, sealed, null);
        }

        public Content(String title, String thought, String feeling, String location, Instant occurredAt, Instant revealAt) {
            this(title, thought, feeling, location, occurredAt, revealAt, null, null);
        }
    }

    /**
     * Content fields encrypted on the device with the sealed vault's data key {@code keyVersion}
     * (AES-256-GCM, AAD = memoryId:field, base64). The server stores the bytes and never decrypts them.
     */
    public record SealedContent(int keyVersion, String title, String thought, String feeling, String location,
                                List<String> searchTokens, String coordinates) {

        public SealedContent(int keyVersion, String title, String thought, String feeling, String location,
                             List<String> searchTokens) {
            this(keyVersion, title, thought, feeling, location, searchTokens, null);
        }

        public SealedContent(int keyVersion, String title, String thought, String feeling, String location) {
            this(keyVersion, title, thought, feeling, location, null, null);
        }
    }

    /** Blind tokens are 16 bytes (SearchTokenizer); a memory of 20,000 characters yields well under this. */
    static final int MAX_SEARCH_TOKENS = 5_000;

    private static List<byte[]> blindTokens(List<String> base64, String field) {
        if (base64 == null) {
            return List.of();
        }
        if (base64.size() > MAX_SEARCH_TOKENS) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", field + " has more than " + MAX_SEARCH_TOKENS + " tokens");
        }
        List<byte[]> out = new ArrayList<>(base64.size());
        for (String t : base64) {
            byte[] token = app.lock.crypto.E2eFormats.decode(t, field);
            if (token.length != 16) {
                throw ApiException.badRequest("INVALID_E2E_PAYLOAD", field + " entries must be 16-byte tokens");
            }
            out.add(token);
        }
        return out;
    }

    // ---- Create / edit drafts -------------------------------------------------------------------

    @Transactional
    public MemoryView create(UUID vaultId, UUID userId, Content content, String idempotencyKey) {
        return create(vaultId, userId, null, content, idempotencyKey);
    }

    /**
     * Creates a memory in a sealed vault under an id the client generated: its ciphertext is bound to
     * that id (AAD), so the id must be known before encrypting. Retrying with the same id is idempotent.
     */
    @Transactional
    public MemoryView createWithId(UUID vaultId, UUID userId, UUID memoryId, Content content, String idempotencyKey) {
        if (memoryId == null) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "id is required: a sealed memory's ciphertext is bound to it");
        }
        var existing = memories.findById(memoryId);
        if (existing.isPresent()) {
            if (existing.get().getCreatedBy().equals(userId) && existing.get().getVaultId().equals(vaultId)) {
                return toView(existing.get(), vaults.requireMember(vaultId, userId));
            }
            throw ApiException.conflict("ID_TAKEN", "Choose a different memory id");
        }
        return create(vaultId, userId, memoryId, content, idempotencyKey);
    }

    private MemoryView create(UUID vaultId, UUID userId, UUID clientId, Content content, String idempotencyKey) {
        VaultAccess access = vaults.requireMember(vaultId, userId);
        if (idempotencyKey != null) {
            var existing = memories.findByCreatedByAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().getVaultId().equals(vaultId)) {
                    throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was used for a different request");
                }
                return toView(existing.get(), access);
            }
        }
        Instant now = now();
        // A sealed vault's client chose the id: its ciphertext is bound to it (AAD), so it must be known up front.
        if (access.isSealed() != (clientId != null)) {
            throw ApiException.badRequest(access.isSealed() ? "INVALID_E2E_PAYLOAD" : "NOT_A_SEALED_VAULT", access.isSealed()
                    ? "A sealed vault needs encrypted content (sealed) and the id it was encrypted for (id)"
                    : "This vault is encrypted on the server; send plaintext fields");
        }
        UUID memoryId = clientId != null ? clientId : UUID.randomUUID();
        Memory memory = new Memory(memoryId, vaultId, userId, access.keyVersion(), idempotencyKey, now);
        applyContent(memory, access, content, now);
        memories.save(memory);
        index(memory, access, content);
        audit.record(AuditEventType.MEMORY_CREATED, userId, vaultId, memory.getId());
        return toView(memory, access);
    }

    @Transactional
    public MemoryView updateDraft(UUID memoryId, UUID userId, Content content) {
        Memory memory = requireDraftOwnedBy(memoryId, userId);
        VaultAccess access = vaults.requireMember(memory.getVaultId(), userId);
        applyContent(memory, access, content, now());
        index(memory, access, content);
        audit.record(AuditEventType.MEMORY_UPDATED, userId, memory.getVaultId(), memoryId);
        return toView(memory, access);
    }

    /** Drafts are not preserved yet, so they can be thrown away. Locked memories cannot. */
    @Transactional
    public void discardDraft(UUID memoryId, UUID userId) {
        Memory memory = requireDraftOwnedBy(memoryId, userId);
        List<String> keys = assets.findByMemoryIdOrderByCreatedAtAsc(memoryId).stream().map(MemoryAsset::getObjectKey).toList();
        memories.delete(memory);
        audit.record(AuditEventType.DRAFT_DISCARDED, userId, memory.getVaultId(), memoryId);
        outbox.publish("memory", memoryId, "memory.draft.discarded", Map.of("objectKeys", keys));
    }

    private void applyContent(Memory memory, VaultAccess access, Content c, Instant now) {
        if (c.revealAt() != null && !c.revealAt().isAfter(now)) {
            throw ApiException.badRequest("INVALID_REVEAL_AT", "revealAt must be in the future");
        }
        if (access.isSealed()) {
            applySealedContent(memory, access, c, now);
            return;
        }
        if (c.sealed() != null) {
            throw ApiException.badRequest("NOT_A_SEALED_VAULT", "This vault is encrypted on the server; send plaintext fields");
        }
        if (c.title() == null || c.title().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "title: must not be blank");
        }
        if (c.coordinates() != null && !c.coordinates().valid()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "coordinates: latitude must be -90..90 and longitude -180..180");
        }
        // Drafts are (re-)encrypted under the current key, so a draft edited after a rotation moves to it.
        ContentCipher.WrappedKey key = access.currentKey();
        UUID id = memory.getId();
        memory.writeContent(access.keyVersion(),
                cipher.encrypt(key, id, TITLE, c.title()),
                cipher.encrypt(key, id, THOUGHT, c.thought()),
                cipher.encrypt(key, id, FEELING, c.feeling()),
                cipher.encrypt(key, id, LOCATION, c.location()),
                cipher.encrypt(key, id, COORDINATES, c.coordinates() == null ? null : c.coordinates().format()),
                c.occurredAt() != null ? c.occurredAt() : now,
                c.revealAt(),
                now);
    }

    /** Sealed vault (roadmap 3.6): stores device-encrypted fields as they are. */
    private void applySealedContent(Memory memory, VaultAccess access, Content c, Instant now) {
        if (c.title() != null || c.thought() != null || c.feeling() != null || c.location() != null || c.coordinates() != null) {
            throw ApiException.badRequest("PLAINTEXT_IN_SEALED_VAULT",
                    "A sealed vault never receives plaintext: encrypt every field on the device and send it under sealed");
        }
        SealedContent e = c.sealed();
        if (e == null) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "sealed is required in a sealed vault");
        }
        sealedKeys.requireCurrentKey(access, e.keyVersion());
        memory.writeContent(e.keyVersion(),
                app.lock.crypto.E2eFormats.content(e.title(), "sealed.title", 200, true),
                app.lock.crypto.E2eFormats.content(e.thought(), "sealed.thought", 20_000, false),
                app.lock.crypto.E2eFormats.content(e.feeling(), "sealed.feeling", 200, false),
                app.lock.crypto.E2eFormats.content(e.location(), "sealed.location", 300, false),
                app.lock.crypto.E2eFormats.content(e.coordinates(), "sealed.coordinates", 40, false),
                c.occurredAt() != null ? c.occurredAt() : now,
                c.revealAt(),
                now);
    }

    /** Blind index for search (roadmap 3.4), under the same key version as the content. */
    private void index(Memory memory, VaultAccess access, Content c) {
        em.flush(); // the index rows reference the memory row
        if (access.isSealed()) {
            // Roadmap 3.6f: tokens computed on the device; none sent means "not searchable".
            searchIndex.indexBlind(memory.getId(), memory.getVaultId(), blindTokens(c.sealed().searchTokens(), "sealed.searchTokens"));
            return;
        }
        searchIndex.index(memory.getId(), memory.getVaultId(), access.currentKey(), c.title(), c.thought(), c.feeling(), c.location());
    }

    // ---- Lock lifecycle ------------------------------------------------------------------------

    /**
     * DRAFT → SEALING. Content is frozen and hashed now; it becomes permanently LOCKED when the
     * cooling-off window ends. Idempotent: locking an already sealing or locked memory changes
     * nothing and records no new events (design doc §29).
     */
    @Transactional
    public MemoryView lock(UUID memoryId, UUID userId) {
        Memory memory = memories.findForUpdate(memoryId).orElseThrow(() -> ApiException.notFound("Memory"));
        VaultAccess access = requireReadable(memory, userId);
        if (memory.getState().isFrozen()) {
            return toView(memory, access);
        }
        requireCreator(memory, userId);

        List<MemoryAsset> memoryAssets = assets.findByMemoryIdOrderByCreatedAtAsc(memoryId);
        // Usable means uploaded, verified AND scanned clean (roadmap 2.2): nothing unscanned is ever preserved.
        List<UUID> notReady = memoryAssets.stream().filter(a -> !a.isUsable()).map(MemoryAsset::getId).toList();
        if (!notReady.isEmpty()) {
            throw ApiException.conflict("ASSETS_NOT_READY",
                    "Wait for these media files to finish their safety scan, or remove them, before locking: " + notReady);
        }
        // Assets are attached while the memory is still a DRAFT: the asset trigger forbids changes afterwards.
        memoryAssets.forEach(MemoryAsset::markAttached);
        em.flush();

        Instant now = now();
        Duration coolingOff = props.memory().coolingOff();
        memory.startSealing(ContentHasher.hash(memory, memoryAssets), now.plus(coolingOff), now);
        audit.record(AuditEventType.MEMORY_SEALING, userId, memory.getVaultId(), memoryId,
                Map.of("sealingUntil", memory.getSealingUntil().toString(), "contentHash", memory.getContentHash()));
        if (coolingOff.isZero() || coolingOff.isNegative()) {
            em.flush();
            completeLock(memory, now);
        }
        return toView(memory, access);
    }

    /** Undo during the cooling-off window: SEALING → DRAFT. */
    @Transactional
    public MemoryView cancelLock(UUID memoryId, UUID userId) {
        Memory memory = memories.findForUpdate(memoryId).orElseThrow(() -> ApiException.notFound("Memory"));
        VaultAccess access = requireReadable(memory, userId);
        requireCreator(memory, userId);
        memory.cancelSealing(now());
        audit.record(AuditEventType.MEMORY_SEAL_CANCELLED, userId, memory.getVaultId(), memoryId);
        return toView(memory, access);
    }

    /** Called by the scheduler: completes every memory whose cooling-off window has ended. */
    @Transactional
    public int completeDueSeals(int batchSize) {
        Instant now = now();
        List<Memory> due = memories.lockSealingDue(now, batchSize);
        due.forEach(m -> completeLock(m, now));
        return due.size();
    }

    private void completeLock(Memory memory, Instant now) {
        memory.completeLock(now);
        metrics.memoryLocked();
        audit.record(AuditEventType.MEMORY_LOCKED, memory.getCreatedBy(), memory.getVaultId(), memory.getId(),
                Map.of("contentHash", memory.getContentHash()));
        List<String> keys = assets.findByMemoryIdOrderByCreatedAtAsc(memory.getId()).stream().map(MemoryAsset::getObjectKey).toList();
        Map<String, Object> payload = new HashMap<>();
        payload.put("vaultId", memory.getVaultId().toString());
        payload.put("createdBy", memory.getCreatedBy().toString());
        payload.put("objectKeys", keys);
        if (memory.getRevealAt() != null) {
            payload.put("revealAt", memory.getRevealAt().toString());
        }
        outbox.publish("memory", memory.getId(), "memory.locked", payload);
    }

    @Transactional
    public MemoryView archive(UUID memoryId, UUID userId, boolean archived) {
        Memory memory = load(memoryId);
        VaultAccess access = requireReadable(memory, userId);
        requireCreator(memory, userId);
        if (archived) {
            memory.archive(now());
            audit.record(AuditEventType.MEMORY_ARCHIVED, userId, memory.getVaultId(), memoryId);
        } else {
            memory.unarchive(now());
        }
        return toView(memory, access);
    }

    // ---- Read / timeline / relive ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public MemoryView get(UUID memoryId, UUID userId) {
        Memory memory = load(memoryId);
        return toView(memory, requireReadable(memory, userId));
    }

    public record TimelinePage(List<MemoryView> memories, TimelineCursor next) {}

    /**
     * One timeline page, newest first. Pass the previous page's {@code next} as {@code after}, or null
     * for the first page. {@code next} is null when there is nothing more.
     */
    @Transactional(readOnly = true)
    public TimelinePage timeline(UUID vaultId, UUID userId, Instant from, Instant to, MemoryState state,
                                 TimelineCursor after, int limit) {
        VaultAccess access = vaults.requireMember(vaultId, userId);
        Instant lower = from != null ? from : Instant.EPOCH;
        Instant upper = to != null ? to : Instant.parse("9999-12-31T00:00:00Z");
        int size = Math.clamp(limit, 1, 200);
        // Fetch one extra row to know whether another page exists without a count query.
        List<Memory> found = memories.timeline(vaultId, userId, lower, upper, state,
                after == null ? null : after.occurredAt(), after == null ? null : after.id(), PageRequest.of(0, size + 1));
        boolean more = found.size() > size;
        List<MemoryView> page = found.stream().limit(size).map(m -> toSummary(m, access)).toList();
        TimelineCursor next = more ? new TimelineCursor(page.getLast().occurredAt(), page.getLast().id()) : null;
        return new TimelinePage(page, next);
    }

    public record ReliveView(MemoryView memory, long daysSinceLocked, List<ReflectionView> reflections, List<MemoryView> samePeriod) {}

    /** Relive is a product experience, not just a GET (design doc §34). */
    @Transactional
    public ReliveView relive(UUID memoryId, UUID userId) {
        Memory memory = load(memoryId);
        VaultAccess access = requireReadable(memory, userId);
        if (!memory.getState().isLocked()) {
            throw ApiException.conflict("NOT_LOCKED", "Only locked memories can be relived");
        }
        Instant now = now();
        if (memory.isSealedCapsule(now)) {
            throw ApiException.conflict("CAPSULE_SEALED", "This time capsule opens on " + memory.getRevealAt());
        }
        audit.record(AuditEventType.MEMORY_RELIVED, userId, memory.getVaultId(), memoryId);
        long days = Duration.between(memory.getLockedAt(), now).toDays();
        List<MemoryView> nearby = memories.samePeriod(memory.getVaultId(), memoryId,
                        memory.getOccurredAt().minus(SAME_PERIOD_WINDOW), memory.getOccurredAt().plus(SAME_PERIOD_WINDOW),
                        PageRequest.of(0, 10))
                .stream().map(m -> toSummary(m, access)).toList();
        return new ReliveView(toView(memory, access), days, reflectionViews(memory, access), nearby);
    }

    /** "You locked this N years ago today": locked memories from this calendar day in earlier years. */
    @Transactional(readOnly = true)
    public List<MemoryView> onThisDay(UUID userId) {
        ZonedDateTime today = now().atZone(ZoneOffset.UTC);
        Instant startOfToday = today.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return memories.onThisDay(userId, today.getMonthValue(), today.getDayOfMonth(), startOfToday).stream()
                .map(m -> toSummary(m, vaults.requireMember(m.getVaultId(), userId)))
                .toList();
    }

    // ---- Search (roadmap 3.4) ------------------------------------------------------------------

    /**
     * Memories in a vault matching every word of {@code query} (prefixes of 3+ letters count), newest
     * first. Same visibility as the timeline: drafts only for their creator; a sealed capsule matches
     * only once it has opened, so a search can't reveal what it says.
     */
    @Transactional(readOnly = true)
    public List<MemoryView> search(UUID vaultId, UUID userId, String query, int limit) {
        VaultAccess access = vaults.requireMember(vaultId, userId);
        if (access.isSealed()) {
            throw ApiException.badRequest("SEALED_SEARCH_NEEDS_TOKENS",
                    "Search a sealed vault with POST /vaults/{id}/search and tokens computed on your device");
        }
        int size = Math.clamp(limit, 1, 100);
        Instant now = now();
        List<UUID> ids = searchIndex.search(vaultId, access.keys().values(), query, 1000);
        return searchResults(ids, userId, access, size, now);
    }

    /** Sealed vault search (roadmap 3.6f): one list of candidate tokens per query word, from the device. */
    @Transactional(readOnly = true)
    public List<MemoryView> searchSealed(UUID vaultId, UUID userId, List<List<String>> wordTokens, int limit) {
        VaultAccess access = vaults.requireMember(vaultId, userId);
        if (!access.isSealed()) {
            throw ApiException.badRequest("NOT_A_SEALED_VAULT", "Search this vault with GET /vaults/{id}/search?q=");
        }
        if (wordTokens == null || wordTokens.isEmpty() || wordTokens.size() > 20) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "words must hold 1 to 20 token lists");
        }
        List<List<byte[]>> decoded = new ArrayList<>();
        for (List<String> word : wordTokens) {
            if (word == null || word.isEmpty() || word.size() > 200) {
                throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "each word needs 1 to 200 tokens");
            }
            decoded.add(blindTokens(word, "words"));
        }
        List<UUID> ids = searchIndex.searchBlind(vaultId, decoded, 1000);
        return searchResults(ids, userId, access, Math.clamp(limit, 1, 100), now());
    }

    private List<MemoryView> searchResults(List<UUID> ids, UUID userId, VaultAccess access, int size, Instant now) {
        return memories.findAllById(ids).stream()
                .filter(m -> m.getState() != MemoryState.DRAFT || m.getCreatedBy().equals(userId))
                .filter(m -> !m.isSealedCapsule(now))
                .sorted(java.util.Comparator.comparing(Memory::getOccurredAt).reversed().thenComparing(Memory::getId))
                .limit(size)
                .map(m -> toSummary(m, access))
                .toList();
    }

    /**
     * Sealed vault (roadmap 3.6f): replaces a memory's device-computed search tokens, e.g. for memories
     * written before on-device search existed. Only the creator: a member can't rewrite how someone
     * else's memory is found. Tokens aren't content, so this is allowed after locking too.
     */
    @Transactional
    public void replaceSealedSearchTokens(UUID memoryId, UUID userId, List<String> tokens) {
        Memory memory = load(memoryId);
        VaultAccess access = requireReadable(memory, userId);
        if (!access.isSealed()) {
            throw ApiException.badRequest("NOT_A_SEALED_VAULT", "The server indexes this vault itself");
        }
        requireCreator(memory, userId);
        List<byte[]> blind = blindTokens(tokens, "tokens");
        searchIndex.indexBlind(memoryId, memory.getVaultId(), blind);
        if (blind.isEmpty()) {
            searchIndex.markEmpty(memoryId); // the device found no words: stop offering it for re-indexing
        }
    }

    /** Sealed vault: ids of the caller's own memories still waiting for device-computed search tokens. */
    @Transactional(readOnly = true)
    public List<UUID> unindexedSealed(UUID vaultId, UUID userId, int limit) {
        VaultAccess access = vaults.requireMember(vaultId, userId);
        if (!access.isSealed()) {
            throw ApiException.badRequest("NOT_A_SEALED_VAULT", "The server indexes this vault itself");
        }
        return searchIndex.unindexedSealed(vaultId, userId, now(), Math.max(1, Math.min(limit, 200)));
    }

    /** Backfill for memories written before 3.4: indexes one memory from its stored ciphertext. */
    @Transactional
    public void reindex(UUID memoryId) {
        Memory m = memories.findById(memoryId).orElse(null);
        if (m == null) {
            return;
        }
        VaultAccess access = vaults.systemAccess(m.getVaultId());
        if (access.isSealed()) {
            return; // the server can't read it; devices index sealed memories (unindexedSealed)
        }
        ContentCipher.WrappedKey key = access.key(m.getKeyVersion());
        UUID id = m.getId();
        String title = cipher.decrypt(key, id, TITLE, m.getTitleCt());
        String thought = cipher.decrypt(key, id, THOUGHT, m.getThoughtCt());
        String feeling = cipher.decrypt(key, id, FEELING, m.getFeelingCt());
        String location = cipher.decrypt(key, id, LOCATION, m.getLocationCt());
        searchIndex.index(id, m.getVaultId(), key, title, thought, feeling, location);
        if (app.lock.search.SearchTokenizer.indexTerms(String.join(" ",
                java.util.Objects.toString(title, ""), java.util.Objects.toString(thought, ""),
                java.util.Objects.toString(feeling, ""), java.util.Objects.toString(location, ""))).isEmpty()) {
            searchIndex.markEmpty(id);
        }
    }

    // ---- Reflections (append-only context) ------------------------------------------------------

    /** {@code body} for server-side vaults; {@code sealedBody} (base64, AAD = reflectionId:reflection) in a sealed one. */
    public record ReflectionView(UUID id, UUID authorId, String body, Instant createdAt, Integer keyVersion, String sealedBody) {

        public ReflectionView(UUID id, UUID authorId, String body, Instant createdAt) {
            this(id, authorId, body, createdAt, null, null);
        }
    }

    /** Sealed vault reflection: encrypted on the device under {@code reflectionId}, which the client chose. */
    @Transactional
    public ReflectionView addSealedReflection(UUID memoryId, UUID userId, UUID reflectionId, int keyVersion, String sealedBody) {
        Memory memory = load(memoryId);
        VaultAccess access = requireReadable(memory, userId);
        if (!access.isSealed()) {
            throw ApiException.badRequest("NOT_A_SEALED_VAULT", "This vault is encrypted on the server; send body");
        }
        requireReflectable(memory);
        if (reflectionId == null) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "id is required: the reflection's ciphertext is bound to it");
        }
        if (reflections.existsById(reflectionId)) {
            throw ApiException.conflict("ID_TAKEN", "Choose a different reflection id");
        }
        sealedKeys.requireCurrentKey(access, keyVersion);
        byte[] ct = app.lock.crypto.E2eFormats.content(sealedBody, "sealedBody", 10_000, true);
        Reflection r = reflections.save(new Reflection(reflectionId, memoryId, userId, ct, keyVersion, now()));
        audit.record(AuditEventType.REFLECTION_ADDED, userId, memory.getVaultId(), memoryId);
        return new ReflectionView(r.getId(), userId, null, r.getCreatedAt(), keyVersion, sealedBody);
    }

    private void requireReflectable(Memory memory) {
        if (!memory.getState().isLocked()) {
            throw ApiException.conflict("NOT_LOCKED", "Reflections can only be added to locked memories");
        }
        if (memory.isSealedCapsule(now())) {
            throw ApiException.conflict("CAPSULE_SEALED", "This time capsule has not opened yet");
        }
    }

    @Transactional
    public ReflectionView addReflection(UUID memoryId, UUID userId, String body) {
        Memory memory = load(memoryId);
        VaultAccess access = requireReadable(memory, userId);
        if (access.isSealed()) {
            throw ApiException.badRequest("PLAINTEXT_IN_SEALED_VAULT", "A sealed vault never receives plaintext: send sealedBody");
        }
        requireReflectable(memory);
        UUID id = UUID.randomUUID();
        Reflection r = reflections.save(new Reflection(id, memoryId, userId,
                cipher.encrypt(access.currentKey(), id, REFLECTION, body), access.keyVersion(), now()));
        audit.record(AuditEventType.REFLECTION_ADDED, userId, memory.getVaultId(), memoryId);
        return new ReflectionView(r.getId(), userId, body, r.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ReflectionView> listReflections(UUID memoryId, UUID userId) {
        Memory memory = load(memoryId);
        return reflectionViews(memory, requireReadable(memory, userId));
    }

    private List<ReflectionView> reflectionViews(Memory memory, VaultAccess access) {
        if (access.isSealed()) {
            java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
            return reflections.findByMemoryIdOrderByCreatedAtAsc(memory.getId()).stream()
                    .map(r -> new ReflectionView(r.getId(), r.getAuthorId(), null, r.getCreatedAt(), r.getKeyVersion(),
                            b64.encodeToString(r.getBodyCt())))
                    .toList();
        }
        return reflections.findByMemoryIdOrderByCreatedAtAsc(memory.getId()).stream()
                .map(r -> new ReflectionView(r.getId(), r.getAuthorId(),
                        cipher.decrypt(access.key(r.getKeyVersion()), r.getId(), REFLECTION, r.getBodyCt()), r.getCreatedAt()))
                .toList();
    }

    // ---- Proof of preservation ------------------------------------------------------------------

    public record IntegrityReport(UUID memoryId, MemoryState state, boolean intact, String sealedHash, String currentHash,
                                  Instant lockedAt, long daysPreserved, int assetsChecked, List<UUID> assetFailures) {}

    /** Recomputes the content hash and checks every stored object's checksum against its record. */
    @Transactional(readOnly = true)
    public IntegrityReport verifyIntegrity(UUID memoryId, UUID userId) {
        Memory memory = load(memoryId);
        requireReadable(memory, userId);
        if (!memory.getState().isFrozen()) {
            throw ApiException.conflict("NOT_LOCKED", "Only sealed or locked memories have a preservation proof");
        }
        List<MemoryAsset> memoryAssets = assets.findByMemoryIdOrderByCreatedAtAsc(memoryId);
        String current = ContentHasher.hash(memory, memoryAssets);
        List<UUID> failures = new ArrayList<>();
        for (MemoryAsset a : memoryAssets) {
            boolean ok = storage.head(a.getObjectKey())
                    .filter(info -> info.sizeBytes() == a.getSizeBytes())
                    .filter(info -> info.checksumSha256Base64() == null || info.checksumSha256Base64().equals(a.getChecksumSha256()))
                    .isPresent();
            if (!ok) {
                failures.add(a.getId());
            }
        }
        boolean intact = current.equals(memory.getContentHash()) && failures.isEmpty();
        if (!intact) {
            metrics.integrityFailed();
        }
        long days = memory.getLockedAt() == null ? 0 : Duration.between(memory.getLockedAt(), now()).toDays();
        return new IntegrityReport(memoryId, memory.getState(), intact, memory.getContentHash(), current,
                memory.getLockedAt(), days, memoryAssets.size(), failures);
    }

    // ---- Deliberate, delayed deletion -----------------------------------------------------------

    public record DeletionView(UUID id, UUID memoryId, DeletionRequest.Status status, Instant requestedAt, Instant executeAfter) {
        static DeletionView of(DeletionRequest d) {
            return new DeletionView(d.getId(), d.getMemoryId(), d.getStatus(), d.getRequestedAt(), d.getExecuteAfter());
        }
    }

    /** Requires re-authentication, and the memory is purged only after a grace period, during which it can be cancelled. */
    @Transactional
    public DeletionView requestDeletion(UUID memoryId, UUID userId, String password, String mfaCode, String reason) {
        Memory memory = load(memoryId);
        requireReadable(memory, userId);
        requireCreator(memory, userId);
        if (!memory.getState().isLocked()) {
            throw ApiException.conflict("NOT_LOCKED", "Drafts can be discarded directly; sealing memories can be cancelled");
        }
        identity.verifyPassword(userId, password);
        mfa.require(userId, mfaCode);
        if (deletions.findByMemoryIdAndStatus(memoryId, DeletionRequest.Status.PENDING).isPresent()) {
            throw ApiException.conflict("DELETION_PENDING", "A deletion request is already pending");
        }
        Instant now = now();
        DeletionRequest request = deletions.save(new DeletionRequest(memoryId, userId, reason, now,
                now.plus(props.memory().deletionGracePeriod())));
        audit.record(AuditEventType.DELETION_REQUESTED, userId, memory.getVaultId(), memoryId,
                Map.of("executeAfter", request.getExecuteAfter().toString()));
        outbox.publish("memory", memoryId, "memory.deletion.requested",
                Map.of("executeAfter", request.getExecuteAfter().toString(), "userId", userId.toString()));
        return DeletionView.of(request);
    }

    @Transactional
    public DeletionView cancelDeletion(UUID memoryId, UUID userId) {
        Memory memory = load(memoryId);
        requireReadable(memory, userId);
        DeletionRequest request = deletions.findByMemoryIdAndStatus(memoryId, DeletionRequest.Status.PENDING)
                .filter(d -> d.getRequestedBy().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Pending deletion request"));
        request.cancel(now());
        audit.record(AuditEventType.DELETION_CANCELLED, userId, memory.getVaultId(), memoryId);
        outbox.publish("memory", memoryId, "memory.deletion.cancelled", Map.of());
        return DeletionView.of(request);
    }

    /** Called by the scheduler: purges memories whose deletion grace period has passed. */
    @Transactional
    public int executeDueDeletions(int batchSize) {
        Instant now = now();
        List<DeletionRequest> due = deletions.lockDue(now, batchSize);
        if (due.isEmpty()) {
            return 0;
        }
        // Transaction-local bypass of the immutability triggers. This is the only code path that sets it.
        em.createNativeQuery("SELECT set_config('lock.admin_purge', 'on', true)").getSingleResult();
        for (DeletionRequest request : due) {
            purge(request, now);
        }
        em.createNativeQuery("SELECT set_config('lock.admin_purge', 'off', true)").getSingleResult();
        return due.size();
    }

    private void purge(DeletionRequest request, Instant now) {
        Memory memory = memories.findById(request.getMemoryId()).orElse(null);
        request.complete(now);
        if (memory == null) {
            return;
        }
        purgeMemory(memory, request.getRequestedBy(), Map.of("deletionRequestId", request.getId().toString()));
    }

    /** Removes a memory with its reflections and assets. Caller must have enabled the trigger bypass. */
    private void purgeMemory(Memory memory, UUID actorId, Map<String, Object> reason) {
        List<String> keys = assets.findByMemoryIdOrderByCreatedAtAsc(memory.getId()).stream().map(MemoryAsset::getObjectKey).toList();
        reflections.deleteAll(reflections.findByMemoryIdOrderByCreatedAtAsc(memory.getId()));
        assets.deleteAll(assets.findByMemoryIdOrderByCreatedAtAsc(memory.getId()));
        memories.delete(memory);
        em.flush();
        Map<String, Object> metadata = new HashMap<>(reason);
        metadata.put("contentHash", String.valueOf(memory.getContentHash()));
        audit.record(AuditEventType.MEMORY_PURGED, actorId, memory.getVaultId(), memory.getId(), metadata);
        outbox.publish("memory", memory.getId(), "memory.purged", Map.of("objectKeys", keys));
        metrics.memoryPurged();
    }

    /**
     * Account deletion, Policy C (roadmap 2.5): every memory in the user's personal vaults is purged,
     * whatever its state. In shared vaults only the user's drafts go (nobody else could ever see them);
     * their locked contributions stay for the other members. Returns the number of memories purged.
     */
    @Transactional
    public int purgeForAccountDeletion(UUID userId, List<UUID> personalVaultIds) {
        Instant now = now();
        Map<String, Object> reason = Map.of("reason", "account_deleted");
        em.createNativeQuery("SELECT set_config('lock.admin_purge', 'on', true)").getSingleResult();
        int purged = 0;
        for (UUID vaultId : personalVaultIds) {
            for (Memory m : memories.findByVaultId(vaultId)) {
                deletions.findByMemoryIdAndStatus(m.getId(), DeletionRequest.Status.PENDING).ifPresent(d -> d.complete(now));
                purgeMemory(m, userId, reason);
                purged++;
            }
        }
        for (Memory draft : memories.findByCreatedByAndState(userId, MemoryState.DRAFT)) {
            purgeMemory(draft, userId, reason);
            purged++;
        }
        em.createNativeQuery("SELECT set_config('lock.admin_purge', 'off', true)").getSingleResult();
        return purged;
    }

    // ---- Authorization helpers (also used by the media module) ----------------------------------

    /** Membership check plus draft privacy: drafts are visible only to their creator. */
    @Transactional(readOnly = true)
    public VaultAccess requireReadable(Memory memory, UUID userId) {
        VaultAccess access = vaults.requireMember(memory.getVaultId(), userId);
        if (memory.getState() == MemoryState.DRAFT && !memory.getCreatedBy().equals(userId)) {
            throw ApiException.notFound("Memory");
        }
        return access;
    }

    @Transactional(readOnly = true)
    public Memory requireDraftOwnedBy(UUID memoryId, UUID userId) {
        Memory memory = load(memoryId);
        requireReadable(memory, userId);
        requireCreator(memory, userId);
        if (memory.getState() != MemoryState.DRAFT) {
            throw ApiException.conflict("MEMORY_IMMUTABLE", "Memory is " + memory.getState() + "; its content cannot change");
        }
        return memory;
    }

    /** Locks the memory row so asset changes serialise with a concurrent lock request. */
    @Transactional
    public Memory requireDraftOwnedByForUpdate(UUID memoryId, UUID userId) {
        memories.findForUpdate(memoryId).orElseThrow(() -> ApiException.notFound("Memory"));
        return requireDraftOwnedBy(memoryId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isSealedVault(UUID vaultId) {
        return vaults.systemAccess(vaultId).isSealed();
    }

    @Transactional(readOnly = true)
    public Memory load(UUID memoryId) {
        return memories.findById(memoryId).orElseThrow(() -> ApiException.notFound("Memory"));
    }

    private static void requireCreator(Memory memory, UUID userId) {
        if (!memory.getCreatedBy().equals(userId)) {
            throw ApiException.forbidden("Only the memory's creator can do this");
        }
    }

    // ---- Mapping -------------------------------------------------------------------------------

    private MemoryView toView(Memory m, VaultAccess access) {
        List<MemoryAsset> memoryAssets = assets.findByMemoryIdOrderByCreatedAtAsc(m.getId());
        Map<UUID, List<AssetDerivative.Variant>> variants = new HashMap<>();
        if (!memoryAssets.isEmpty()) {
            derivatives.findByAssetIdIn(memoryAssets.stream().map(MemoryAsset::getId).toList()).forEach(d ->
                    variants.computeIfAbsent(d.getAssetId(), k -> new ArrayList<>()).add(d.getVariant()));
        }
        List<MemoryView.AssetView> assetViews = memoryAssets.stream()
                .map(a -> MemoryView.AssetView.of(a, variants.getOrDefault(a.getId(), List.of()).stream().sorted().toList()))
                .toList();
        return view(m, access, assetViews);
    }

    private MemoryView toSummary(Memory m, VaultAccess access) {
        return view(m, access, List.of());
    }

    private MemoryView view(Memory m, VaultAccess access, List<MemoryView.AssetView> assetViews) {
        boolean sealed = m.isSealedCapsule(now());
        if (access.isSealed()) {
            // Ciphertext only, and for a closed time capsule not even that: the reveal date still holds.
            java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
            MemoryView.Encrypted encrypted = sealed ? null : new MemoryView.Encrypted(m.getKeyVersion(),
                    b64.encodeToString(m.getTitleCt()),
                    m.getThoughtCt() == null ? null : b64.encodeToString(m.getThoughtCt()),
                    m.getFeelingCt() == null ? null : b64.encodeToString(m.getFeelingCt()),
                    m.getLocationCt() == null ? null : b64.encodeToString(m.getLocationCt()),
                    m.getCoordinatesCt() == null ? null : b64.encodeToString(m.getCoordinatesCt()));
            return new MemoryView(m.getId(), m.getVaultId(), m.getCreatedBy(), m.getState(), null, null, null, null, null,
                    m.getOccurredAt(), m.getRevealAt(), sealed, m.getSealingUntil(), m.getLockedAt(), m.getContentHash(),
                    m.getCreatedAt(), sealed ? List.of() : assetViews, encrypted);
        }
        ContentCipher.WrappedKey key = access.key(m.getKeyVersion());
        UUID id = m.getId();
        return new MemoryView(id, m.getVaultId(), m.getCreatedBy(), m.getState(),
                sealed ? null : cipher.decrypt(key, id, TITLE, m.getTitleCt()),
                sealed ? null : cipher.decrypt(key, id, THOUGHT, m.getThoughtCt()),
                sealed ? null : cipher.decrypt(key, id, FEELING, m.getFeelingCt()),
                sealed ? null : cipher.decrypt(key, id, LOCATION, m.getLocationCt()),
                sealed ? null : MemoryView.Coordinates.parse(cipher.decrypt(key, id, COORDINATES, m.getCoordinatesCt())),
                m.getOccurredAt(), m.getRevealAt(), sealed, m.getSealingUntil(), m.getLockedAt(), m.getContentHash(),
                m.getCreatedAt(), sealed ? List.of() : assetViews, null);
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
