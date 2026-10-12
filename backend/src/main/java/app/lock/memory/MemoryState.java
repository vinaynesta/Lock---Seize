package app.lock.memory;

/**
 * DRAFT → SEALING → LOCKED ⇄ ARCHIVED.
 *
 * <p>SEALING is the cooling-off window (feasibility analysis §4). Content is already frozen and
 * hashed, but the owner may cancel back to DRAFT until the window ends. LOCKED and ARCHIVED are
 * permanent: the only way out is the deliberate, delayed deletion process.
 */
public enum MemoryState {
    DRAFT,
    SEALING,
    LOCKED,
    ARCHIVED;

    public boolean isFrozen() {
        return this != DRAFT;
    }

    public boolean isLocked() {
        return this == LOCKED || this == ARCHIVED;
    }
}
