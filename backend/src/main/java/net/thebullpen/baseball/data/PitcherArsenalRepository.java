package net.thebullpen.baseball.data;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.ArsenalPitch;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads a pitcher's arsenal (Phase 2.1) from {@code pitches}: per pitch type, the throw count and
 * the velocity RANGE (min / avg / max). The frontend arsenal card renders this in place of the
 * fixture pitch-mix.
 *
 * <p>No {@code FINAL}: re-ingested duplicate pitches are rare and do not materially shift a min /
 * avg / max velocity or a usage share, so we skip the full-table merge {@code FINAL} would force
 * and keep the (non-hot, TanStack-cached) profile read responsive. Gated on {@code
 * bullpen.clickhouse.enabled} like the other analytical repos, {@code api} profile only (it backs a
 * read endpoint, not a worker job).
 */
@Repository
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitcherArsenalRepository {

  private static final String SELECT_ARSENAL =
      "SELECT pitch_type AS pitch_type, toUInt64(count()) AS n,"
          + " min(release_speed_mph) AS velo_min,"
          + " avg(release_speed_mph) AS velo_avg,"
          + " max(release_speed_mph) AS velo_max"
          + " FROM pitches"
          + " WHERE pitcher_id = ? AND release_speed_mph IS NOT NULL AND pitch_type != ''"
          + " GROUP BY pitch_type ORDER BY n DESC";

  private final JdbcTemplate jdbc;

  public PitcherArsenalRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * A raw per-type aggregate before the usage share (which needs the across-types total) is filled.
   */
  private record RawArsenal(String pitchType, long count, double min, double avg, double max) {}

  /**
   * A pitcher's arsenal over all seasons, most-thrown pitch type first; empty if the id threw no
   * velocity-tracked pitch.
   */
  public List<ArsenalPitch> findArsenal(long pitcherId) {
    List<RawArsenal> raw =
        jdbc.query(
            SELECT_ARSENAL,
            (rs, i) ->
                new RawArsenal(
                    rs.getString("pitch_type"),
                    rs.getLong("n"),
                    rs.getDouble("velo_min"),
                    rs.getDouble("velo_avg"),
                    rs.getDouble("velo_max")),
            pitcherId);

    long total = raw.stream().mapToLong(RawArsenal::count).sum();
    List<ArsenalPitch> out = new ArrayList<>(raw.size());
    for (RawArsenal r : raw) {
      double usage = total == 0 ? 0.0 : (double) r.count() / total;
      out.add(new ArsenalPitch(r.pitchType(), r.count(), usage, r.min(), r.avg(), r.max()));
    }
    return out;
  }
}
