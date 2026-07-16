package com.ethanluong.ticketreservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Allows more granular transaction boundary. Needed for {@link com.ethanluong.ticketreservation.service.ReservationService#reserve(UUID, UUID)}
 * 
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
