package app.lock;

import app.lock.support.TestPostgres;
import org.springframework.boot.SpringApplication;

/**
 * Runs the API on an embedded PostgreSQL with in-memory media storage: no Docker needed.
 * {@code ./mvnw spring-boot:test-run}. Data is lost on exit.
 */
public class LocalDevApplication {

    public static void main(String[] args) {
        SpringApplication.from(LockApplication::main)
                .with(TestPostgres.class)
                .withAdditionalProfiles("local")
                .run(args);
    }
}
