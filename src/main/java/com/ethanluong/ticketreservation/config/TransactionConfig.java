package com.ethanluong.ticketreservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Boot auto-configures {@link PlatformTransactionManager} but NOT
 * {@link TransactionTemplate}. Declare one for services that need to
 * control transaction boundaries programmatically (e.g. {@code reserve()}
 * in ReservationService, which does Redis work outside the DB transaction
 * so it can compensate on rollback).
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
