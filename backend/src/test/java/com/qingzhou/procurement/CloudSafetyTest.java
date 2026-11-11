package com.qingzhou.procurement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class CloudSafetyTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withInitializer(context->context.getEnvironment().setActiveProfiles("cloud"))
        .withUserConfiguration(CloudSafetyConfig.class)
        .withPropertyValues("DB_URL=jdbc:postgresql://example.neon.tech/neondb?sslmode=verify-full","DB_USER=test_user",
            "DB_PASSWORD=test-only-database-password","BOOTSTRAP_ADMIN_PASSWORD=TestOnlyCloudPassword!2026");
    @Test void acceptsExplicitEncryptedPostgresConfiguration(){runner.run(context->assertThat(context).hasNotFailed());}
    @Test void rejectsLocalOrMissingDatabase(){runner.withPropertyValues("DB_URL=jdbc:h2:file:./data/unsafe").run(context->assertThat(context).hasFailed());}
    @Test void rejectsUnencryptedPostgres(){runner.withPropertyValues("DB_URL=jdbc:postgresql://example.neon.tech/neondb").run(context->assertThat(context).hasFailed());}
    @Test void rejectsThePublicDemoPassword(){runner.withPropertyValues("BOOTSTRAP_ADMIN_PASSWORD=Qingzhou@2026").run(context->assertThat(context).hasFailed());}
    @Test void rejectsMissingDatabaseCredentials(){runner.withPropertyValues("DB_PASSWORD=").run(context->assertThat(context).hasFailed());}
    @Test void acceptsEightCharacterBootstrapPassword(){runner.withPropertyValues("BOOTSTRAP_ADMIN_PASSWORD=Test123!").run(context->assertThat(context).hasNotFailed());}
    @Test void rejectsSevenCharacterBootstrapPassword(){runner.withPropertyValues("BOOTSTRAP_ADMIN_PASSWORD=Test12!").run(context->assertThat(context).hasFailed());}
    @Test void rejectsCloudDemoMode(){runner.withPropertyValues("app.demo=true").run(context->assertThat(context).hasFailed());}
}
