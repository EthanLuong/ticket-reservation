package com.ethanluong.ticketreservation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling lives on its own conditional config (not the app class) so integration
 * tests can switch it off: with it always on, every @SpringBootTest boots the real
 * SagaTimeoutSweeper, which sweeps once a second in the background of the test and
 * races any fixture whose saga sits AWAITING_PAYMENT — tests call sweep() directly
 * instead. Absent property = enabled, so production needs no configuration.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
