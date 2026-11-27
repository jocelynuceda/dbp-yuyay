package com.yuyay.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityDirectorySelectionTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Directories.class);

    @Test
    void usesApisPeruWhenThereIsAToken() {
        runner.withPropertyValues("app.reniec.api-token=un-token-de-prueba")
                .run(context -> assertThat(context)
                        .hasSingleBean(IdentityDirectory.class)
                        .hasSingleBean(ApisPeruIdentityDirectory.class)
                        .doesNotHaveBean(DisabledIdentityDirectory.class));
    }

    @Test
    void fallsBackToDisabledWhenThereIsNoToken() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(IdentityDirectory.class)
                .hasSingleBean(DisabledIdentityDirectory.class)
                .doesNotHaveBean(ApisPeruIdentityDirectory.class));
    }

    @Test
    void anEmptyTokenCountsAsNoToken() {
        runner.withPropertyValues("app.reniec.api-token=")
                .run(context -> assertThat(context)
                        .hasSingleBean(DisabledIdentityDirectory.class)
                        .doesNotHaveBean(ApisPeruIdentityDirectory.class));
    }

    @Test
    void withoutATokenEveryQueryIsUnavailableRatherThanAFailure() {
        runner.run(context -> assertThat(context.getBean(IdentityDirectory.class).findByDni("12345678"))
                .isInstanceOf(IdentityLookup.Unavailable.class));
    }

    @Configuration
    @Import({ApisPeruIdentityDirectory.class, DisabledIdentityDirectory.class})
    static class Directories {
    }
}
