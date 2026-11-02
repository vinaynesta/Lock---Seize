package app.lock.media.scan;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pings clamd every minute and exposes the answer as the {@code lock.scan.up} gauge (1 up, 0 down),
 * so an alert can say "the scanner is down" instead of only "uploads are piling up" (follow-up F3).
 * Deliberately not part of readiness: with clamd down, uploads queue as PENDING_SCAN and are scanned
 * later, so the API should keep serving.
 */
@Component
@ConditionalOnProperty(name = "lock.scan.provider", havingValue = "clamav")
public class ScannerProbe {

    private static final Logger log = LoggerFactory.getLogger(ScannerProbe.class);

    private final ClamAvScanner scanner;
    private final AtomicInteger up = new AtomicInteger(1);

    ScannerProbe(ClamAvScanner scanner, MeterRegistry registry) {
        this.scanner = scanner;
        Gauge.builder("lock.scan.up", up, AtomicInteger::get)
                .description("1 if clamd answered the last PING, 0 if not").register(registry);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT10S")
    public void probe() {
        boolean ok = scanner.ping();
        int previous = up.getAndSet(ok ? 1 : 0);
        if (!ok && previous == 1) {
            log.warn("clamd is not answering PING; uploads will wait in PENDING_SCAN until it is back");
        } else if (ok && previous == 0) {
            log.info("clamd is answering again");
        }
    }

    public int value() {
        return up.get();
    }
}
