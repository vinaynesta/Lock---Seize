package app.lock.identity;

import java.util.UUID;

/** Published in the registration transaction; the vault module reacts by creating the personal vault. */
public record UserCreatedEvent(UUID userId, String displayName) {}
