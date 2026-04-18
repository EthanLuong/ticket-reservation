package com.ethanluong.ticketreservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedV1Successfully() {
        Integer successCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);
        assertThat(successCount).isEqualTo(1);
    }

    @Test
    void allPhaseZeroTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tables).containsAll(Set.of("users", "events", "seats", "reservations"));
    }

    @Test
    void seatsHasVersionColumnForOptimisticLocking() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'seats' AND column_name = 'version'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void activeReservationPerSeatIsEnforcedByPartialUniqueIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes " +
                        "WHERE tablename = 'reservations' AND indexname = 'idx_reservations_active_seat'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
