package app.lock.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under the {@code prod} profile while any secret still has the public default
 * from application.yml (the master key only matters when the KEK is local, not in KMS), or while
 * uploads would only be checked by the dev malware scanner, or emails would only be logged. Without
 * this, a missing env var silently means forgeable JWTs and a published key-encryption key
 * (roadmap 1.4).
 */
@Component
@Profile("prod")
class ProductionSecretsGuard implements InitializingBean {

    /** The dev-only defaults committed in application.yml. Keep in sync with that file. */
    static final String DEV_JWT_SECRET = "ZGV2LW9ubHktand0LXNlY3JldC1jaGFuZ2UtbWUtcGxlYXNlLTEyMzQ1Ng==";
    static final String DEV_MASTER_KEY = "U41EknbduMk8SqoWrNBc3ZvWCZ2xTS/5+K8C9tmG4Sw=";
    static final String DEV_S3_SECRET = "lock-secret";

    private final LockProperties props;

    ProductionSecretsGuard(LockProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        if (DEV_JWT_SECRET.equals(props.jwt().secret())) {
            problems.add("LOCK_JWT_SECRET");
        }
        // With aws-kms the master key is not used to wrap anything, so its default is harmless.
        if (!props.crypto().isAwsKms() && DEV_MASTER_KEY.equals(props.crypto().masterKey())) {
            problems.add("LOCK_MASTER_KEY");
        }
        if ("s3".equals(props.storage().type()) && DEV_S3_SECRET.equals(props.storage().secretKey())) {
            problems.add("LOCK_S3_SECRET_KEY");
        }
        // The dev scanner only knows the EICAR test file: production must scan for real (roadmap 2.2).
        if (props.scan() == null || props.scan().isDev()) {
            problems.add("LOCK_SCAN_PROVIDER");
        }
        // The log sender drops every email: password resets (roadmap 2.6) would silently never arrive.
        if (props.email() == null || props.email().provider() == null || "log".equals(props.email().provider())) {
            problems.add("LOCK_EMAIL_PROVIDER");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with dev-default secrets in the prod profile. Set: "
                    + String.join(", ", problems.stream().sorted().toList()));
        }
    }
}
