package com.ethanluong.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Injectable Clock so tests can pin time (PaymentService envelope timestamps,
 * OutboxPublisher processed_at). Reservation's TransactionConfig also carried a
 * TransactionTemplate bean — not copied: nothing here does programmatic
 * transactions, so only the Clock travels.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
