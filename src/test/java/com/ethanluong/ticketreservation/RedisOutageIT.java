package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.api.exception.GlobalExceptionHandler;
import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the failure-closed contract documented in ADR 0002: when Redis is
 * unreachable, reservation endpoints surface a connection exception that the
 * GlobalExceptionHandler maps to 503 SERVICE_UNAVAILABLE, and no partial state
 * is written to Postgres.
 *
 * <p>Failure injection: {@code @MockitoBean RedissonClient} replaces the real
 * client with one whose {@code getLock(...)} unconditionally throws — semantically
 * equivalent to the connection failing on every Redis call. Avoids the test
 * complexity of physically stopping/restarting the Testcontainers Redis instance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisOutageIT {

    @MockitoBean
    private RedissonClient redisson;

    @Autowired private ReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID userId;
    private UUID seatId;

    @BeforeEach
    void seed() {
        // Simulate Redis unreachable — every getLock() call throws as if the
        // connection pool can't reach the server.
        when(redisson.getLock(anyString())).thenThrow(
                new RedisConnectionException("simulated outage — Redis unreachable"));

        User user = userRepository.save(User.builder()
                .email("outage-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("Outage")
                .build());
        userId = user.getId();

        Event event = eventRepository.save(Event.builder()
                .name("Outage Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("D-1")
                .priceCents(5000L)
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build());
        seatId = seat.getId();
    }

    @AfterEach
    void cleanup() {
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("reserve() surfaces RedisConnectionException when Redis is unreachable, no DB drift")
    void reserve_throwsOnRedisOutage_noPartialState() {
        assertThatThrownBy(() -> reservationService.reserve(userId, seatId))
                .isInstanceOf(RedisConnectionException.class);

        // Fail-closed: nothing was written to Postgres.
        assertThat(reservationRepository.count()).isZero();

        var seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("GlobalExceptionHandler maps RedisConnectionException to 503 ProblemDetail")
    void exceptionHandler_redissonException_mapsTo503() {
        ProblemDetail pd = new GlobalExceptionHandler()
                .onRedisOutage(new RedisConnectionException("test"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getTitle()).isEqualTo("Service temporarily unavailable");
        assertThat(pd.getProperties())
                .containsEntry("retryable", true)
                .containsEntry("retryAfterSeconds", 5);
    }

    @Test
    @DisplayName("GlobalExceptionHandler maps Spring Data RedisConnectionFailureException to 503 ProblemDetail")
    void exceptionHandler_lettuceException_mapsTo503() {
        ProblemDetail pd = new GlobalExceptionHandler()
                .onRedisOutage(new RedisConnectionFailureException("test"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getTitle()).isEqualTo("Service temporarily unavailable");
    }
}
