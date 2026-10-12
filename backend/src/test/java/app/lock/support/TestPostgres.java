package app.lock.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/** A real PostgreSQL per test JVM, so the immutability triggers are exercised, not mocked. No Docker needed. */
@TestConfiguration(proxyBeanMethods = false)
public class TestPostgres {

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    @Primary
    DataSource dataSource() {
        return POSTGRES.getPostgresDatabase();
    }
}
