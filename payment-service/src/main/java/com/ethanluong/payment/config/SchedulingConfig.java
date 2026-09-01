package com.ethanluong.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling lives on its own conditional config (not the app class) so integration
 * tests can switch it off: with it always on, every @SpringBootTest boots the real
 * OutboxPublisher, which drains the outbox once a second in the background and
 * races any fixture that inspects unpublished rows — tests call publish() directly
 * instead. Absent property = enabled, so production needs no configuration.
 * Without this class the relay compiles and boots but silently never runs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
