package app.lock.account;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs due account deletions, one account per transaction, so one failure never blocks the rest. */
@Component
class AccountDeletionJob {

    private static final int MAX_PER_RUN = 50;

    private final AccountService accounts;

    AccountDeletionJob(AccountService accounts) {
        this.accounts = accounts;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT3M")
    void run() {
        for (int i = 0; i < MAX_PER_RUN && accounts.executeNextDue(); i++) {
            // each call commits one account
        }
    }
}
