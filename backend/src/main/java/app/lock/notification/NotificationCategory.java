package app.lock.notification;

/** What a notification is about. Every category but {@link #DELETION_REMINDER} can be turned off. */
public enum NotificationCategory {
    MEMORY_LOCKED,
    CAPSULE_REVEALED,
    ON_THIS_DAY,
    VAULT_INVITATION,
    ASSET_REJECTED,
    EXPORT_READY,
    /** 7 days and 1 day before a memory or the account is deleted. A safety feature: always on. */
    DELETION_REMINDER;

    public boolean isOptional() {
        return this != DELETION_REMINDER;
    }
}
