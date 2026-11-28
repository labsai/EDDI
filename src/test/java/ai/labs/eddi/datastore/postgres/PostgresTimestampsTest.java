/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code TIMESTAMP} (without time zone) column written by one JVM and read by
 * another gives back the instant that was written, whatever zone either runs
 * in.
 * <p>
 * The column is emulated the way the PostgreSQL driver treats it: what is
 * stored is the instant's wall clock in the {@link Calendar}'s zone, or in the
 * JVM's default zone when no calendar is passed. Each JVM's default zone is a
 * parameter of the emulation rather than {@code TimeZone.setDefault}, so two
 * replicas fit in one test and no global state leaks into other tests.
 */
class PostgresTimestampsTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** 2026-11-01: both are 01:30 on a New York wall clock, an hour apart. */
    private static final Instant FIRST_ONE_THIRTY = Instant.parse("2026-11-01T05:30:00Z");
    private static final Instant SECOND_ONE_THIRTY = Instant.parse("2026-11-01T06:30:00Z");

    @Test
    @DisplayName("an instant written by a replica in one zone reads back unchanged on a replica in another")
    void anInstantSurvivesReplicasInDifferentZones() throws Exception {
        var column = new TimestampColumn(KOLKATA, NEW_YORK);
        Instant expiresAt = Instant.parse("2026-08-22T10:00:00Z");

        PostgresTimestamps.setUtc(column.writer(), 1, expiresAt);

        // Through each JVM's own zone the reader sees it 9h30 late: a dead access
        // token served as live.
        assertEquals(expiresAt, PostgresTimestamps.getUtc(column.reader(), "expires_at"));
    }

    @Test
    @DisplayName("the repeated autumn hour of a daylight-saving zone keeps both of its instants apart")
    void theRepeatedAutumnHourKeepsItsInstantsApart() throws Exception {
        for (Instant instant : List.of(FIRST_ONE_THIRTY, SECOND_ONE_THIRTY)) {
            var column = new TimestampColumn(NEW_YORK, NEW_YORK);

            PostgresTimestamps.setUtc(column.writer(), 1, instant);

            // As New York wall clocks both are 01:30, and the second reads back as the
            // first: an expiry that sorts before the creation it follows.
            assertEquals(instant, PostgresTimestamps.getUtc(column.reader(), "expires_at"));
        }
    }

    @Test
    @DisplayName("NULL is written as NULL and read back as null")
    void nullStaysNull() throws Exception {
        var column = new TimestampColumn(KOLKATA, NEW_YORK);

        PostgresTimestamps.setUtc(column.writer(), 1, null);

        assertNull(PostgresTimestamps.getUtc(column.reader(), "last_refresh_at"));
    }

    /**
     * One {@code TIMESTAMP} column, written by a JVM in {@code writerJvm} and read
     * by one in {@code readerJvm}.
     */
    private static final class TimestampColumn {

        private final ZoneId writerJvm;
        private final ZoneId readerJvm;
        private LocalDateTime stored;

        TimestampColumn(ZoneId writerJvm, ZoneId readerJvm) {
            this.writerJvm = writerJvm;
            this.readerJvm = readerJvm;
        }

        PreparedStatement writer() throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(invocation -> store(invocation.getArgument(1), writerJvm)).when(statement).setTimestamp(anyInt(), any());
            doAnswer(invocation -> store(invocation.getArgument(1), zoneOf(invocation.getArgument(2)))).when(statement).setTimestamp(anyInt(),
                    any(), any());
            return statement;
        }

        ResultSet reader() throws SQLException {
            ResultSet rows = mock(ResultSet.class);
            when(rows.getTimestamp(anyString())).thenAnswer(invocation -> load(readerJvm));
            when(rows.getTimestamp(anyString(), any(Calendar.class))).thenAnswer(invocation -> load(zoneOf(invocation.getArgument(1))));
            return rows;
        }

        private Object store(Timestamp timestamp, ZoneId zone) {
            stored = timestamp == null ? null : LocalDateTime.ofInstant(timestamp.toInstant(), zone);
            return null;
        }

        private Timestamp load(ZoneId zone) {
            return stored == null ? null : Timestamp.from(stored.atZone(zone).toInstant());
        }

        private static ZoneId zoneOf(Calendar calendar) {
            return calendar.getTimeZone().toZoneId();
        }
    }
}
