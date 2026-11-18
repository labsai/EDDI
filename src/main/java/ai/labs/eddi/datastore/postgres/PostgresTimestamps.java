/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Writes and reads a {@code TIMESTAMP} (without time zone) column as a UTC wall
 * clock.
 * <p>
 * Such a column stores a wall-clock reading and no zone. The driver's
 * one-argument {@code setTimestamp} and {@code getTimestamp} convert through
 * the JVM's default zone, so the instant a row gives back depends on the zone
 * of the replica that wrote it and of the one reading it: replicas a few hours
 * apart move every stored expiry by the difference, and a host in a
 * daylight-saving zone writes the repeated autumn hour twice, so an expiry ten
 * minutes after its creation can read as before it. A UTC {@link Calendar} on
 * both sides pins the wall clock to UTC, which no JVM or session setting moves.
 * <p>
 * SQL that stamps such a column from the database's clock has the same problem
 * one step earlier: {@code CURRENT_TIMESTAMP} is a {@code TIMESTAMPTZ}, and its
 * cast to {@code TIMESTAMP} goes through the session's {@code TimeZone}, which
 * the driver sets from the JVM's. Spell it
 * {@code (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')}.
 */
public final class PostgresTimestamps {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private PostgresTimestamps() {
    }

    /** Binds {@code instant}, or SQL NULL for {@code null}, as a UTC wall clock. */
    public static void setUtc(PreparedStatement statement, int index, Instant instant) throws SQLException {
        statement.setTimestamp(index, instant == null ? null : Timestamp.from(instant), utc());
    }

    /** Reads {@code column} as a UTC wall clock; SQL NULL is {@code null}. */
    public static Instant getUtc(ResultSet rows, String column) throws SQLException {
        Timestamp timestamp = rows.getTimestamp(column, utc());
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** A new one per call: a {@link Calendar} is mutable and not thread-safe. */
    private static Calendar utc() {
        return Calendar.getInstance(UTC);
    }
}
