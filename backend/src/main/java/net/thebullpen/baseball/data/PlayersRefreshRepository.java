package net.thebullpen.baseball.data;

import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.ingest.MlbPlayer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writer for the {@code players} ClickHouse dimension (V014): the roster rows the {@code
 * /v1/players/search} autocomplete reads (see {@link PlayerRepository}). DP3 / WS3 - V014 shipped
 * with the read side; this is the producer it was designed around ("the nightly MLB Stats API
 * roster pull", weekly in practice since rosters move slowly).
 *
 * <p>Insert-only by design: {@code players} is a ReplacingMergeTree on {@code updated_at} keyed by
 * {@code id}, so a re-pull re-writes rows and the most recent wins on FINAL reads - no DELETE, no
 * UPDATE.
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled} exactly like {@link PitcherFormRepository} (NOT
 * {@code @ConditionalOnBean(clickhouseDataSource)} - see the crash-loop note there). On both
 * profiles so docker-gated ITs can wire it under {@code api}; only the worker {@link
 * net.thebullpen.baseball.ingest.PlayersRefreshJob} actually calls it.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PlayersRefreshRepository {

  private static final String INSERT =
      "INSERT INTO players (id, name, primary_position, bats, throws, active, team)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";

  // FINAL so a re-pull's re-written rows don't double-count before parts compact.
  private static final String COUNT = "SELECT count() FROM players FINAL";

  private final JdbcTemplate jdbc;

  public PlayersRefreshRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Insert every row (upsert via ReplacingMergeTree semantics - same id replaces on FINAL). Returns
   * the number of rows written. {@code updated_at} takes the column DEFAULT now().
   */
  public int upsertAll(List<MlbPlayer> players) {
    if (players.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(
        INSERT,
        players,
        1_000,
        (ps, p) -> {
          ps.setLong(1, p.id());
          ps.setString(2, p.name());
          ps.setString(3, p.primaryPosition());
          ps.setString(4, p.bats());
          ps.setString(5, p.throwsHand());
          ps.setInt(6, p.active() ? 1 : 0);
          ps.setString(7, p.team());
        });
    return players.size();
  }

  /** Distinct players currently in the dimension. Zero means "never backfilled". */
  public long countAll() {
    Long n = jdbc.queryForObject(COUNT, Long.class);
    return n == null ? 0L : n;
  }
}
