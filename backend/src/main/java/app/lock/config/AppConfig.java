package app.lock.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI lockOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Lock API").version("v1")
                        .description("Create → Lock → Preserve → Relive"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /** Background jobs (sealing, deletion, reconciliation, outbox). Disabled in tests, which drive them directly. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "lock.scheduling-enabled", havingValue = "true")
    static class SchedulingConfig {}
}
