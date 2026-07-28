package com.ethanluong.ticketreservation.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * I5 (audit): the fallback secret in application.properties exists so a fresh
 * clone boots — but any deployment still running it lets anyone who has read
 * the repo mint valid tokens. Fail startup, loudly, if a non-dev profile is
 * active while the dev default is in play. The empty/default profile stays
 * permissive so local bootRun and tests keep working; real deployments declare
 * a profile. M4 replaces this whole arrangement with Secrets Manager.
 */
@Configuration
public class JwtSecretGuard {

    static final String DEV_DEFAULT_MARKER = "dev-only-secret-change-me";
    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "test");

    public JwtSecretGuard(JwtProperties props, Environment env) {
        boolean nonDevProfileActive = Arrays.stream(env.getActiveProfiles())
                .anyMatch(profile -> !DEV_PROFILES.contains(profile));
        if (nonDevProfileActive && props.secret().contains(DEV_DEFAULT_MARKER)) {
            throw new IllegalStateException(
                    "app.security.jwt.secret is still the dev default while a non-dev profile is active — "
                            + "set APP_SECURITY_JWT_SECRET before deploying (audit I5)");
        }
    }
}
