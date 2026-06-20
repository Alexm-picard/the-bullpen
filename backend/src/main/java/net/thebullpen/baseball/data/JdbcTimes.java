package net.thebullpen.baseball.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Tz-explicit reads of the registry SQLite's zone-less datetime columns.
 *
 * <p>SQLite writes {@code CURRENT_TIMESTAMP} as a zone-less {@code "YYYY-MM-DD HH:MM:SS"} string
 * that is, by construction, UTC wall-clock. A bare {@code rs.getTimestamp(col)} binds that string
 * through the JVM default zone, which on the prod box is {@code America/New_York} - so a UTC value
 * is read back with a +4h (EDT) / +5h (EST) skew on every audit timestamp (the symptom:
 * ops/registry rows reading ~4h in the future). Passing a UTC {@link Calendar} pins the wall-clock
 * to UTC regardless of the JVM zone. Same tz-explicit discipline the ClickHouse reads already apply
 * ({@code LivePitchesRepository#SCHEDULED_GAME_MAPPER}, {@code PlayerPredictionsRepository}).
 *
 * <p>NOTE: this is for columns written UTC by the DB ({@code CURRENT_TIMESTAMP}). A column written
 * app-side via a bare {@code setTimestamp(Timestamp.from(instant))} is stored in the JVM zone, so
 * it must be read back bare too (matched pair) - do not route those through here without also
 * making the write tz-explicit and migrating existing rows. See {@code RegistryRepository}'s {@code
 * trained_at}.
 */
public final class JdbcTimes {

  private JdbcTimes() {}

  /**
   * Read {@code col} as an {@link Instant}, interpreting the zone-less SQLite value as UTC. A fresh
   * {@link Calendar} per call: the JDBC read takes its zone, and a per-call instance sidesteps any
   * cross-thread mutation concern with a shared {@code Calendar}.
   */
  public static Instant utcInstant(ResultSet rs, String col) throws SQLException {
    Timestamp ts = rs.getTimestamp(col, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
    return ts == null ? null : ts.toInstant();
  }
}
