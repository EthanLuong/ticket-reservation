package com.ethanluong.ticketreservation.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I5: default JWT secret must not survive into a non-dev profile. Plain unit — no context. */
class JwtSecretGuardTest {

    private static final String DEV_DEFAULT =
            "dev-only-secret-change-me-0123456789abcdef0123456789abcdef";
    private static final String REAL_SECRET =
            "a-genuinely-configured-secret-0123456789abcdef0123456789abcdef";

    private JwtProperties props(String secret) {
        return new JwtProperties(secret, 60, "test-issuer");
    }

    private MockEnvironment envWithProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("prod profile + dev default secret → startup fails")
    void prodWithDefaultSecret_fails() {
        assertThatThrownBy(() -> new JwtSecretGuard(props(DEV_DEFAULT), envWithProfiles("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SECURITY_JWT_SECRET");
    }

    @Test
    @DisplayName("prod profile + real secret → boots")
    void prodWithRealSecret_boots() {
        assertThatCode(() -> new JwtSecretGuard(props(REAL_SECRET), envWithProfiles("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no active profile + dev default → boots (local bootRun stays workable)")
    void defaultProfileWithDefaultSecret_boots() {
        assertThatCode(() -> new JwtSecretGuard(props(DEV_DEFAULT), envWithProfiles()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev profile + dev default → boots")
    void devProfileWithDefaultSecret_boots() {
        assertThatCode(() -> new JwtSecretGuard(props(DEV_DEFAULT), envWithProfiles("dev")))
                .doesNotThrowAnyException();
    }
}
