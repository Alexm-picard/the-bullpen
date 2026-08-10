package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import net.thebullpen.baseball.domain.OpsEvent;
import net.thebullpen.baseball.domain.OpsEventType;
import net.thebullpen.baseball.domain.PagedRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test for {@link OpsEventsRepository} against a fresh tmp SQLite (Flyway runs
 * V001–V015 on boot). Same isolated-tmp-DB pattern as {@code RegistryServiceIT}.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class OpsEventsRepositoryIT {

  @DynamicPropertySource
  static void itDataSource(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-ops-events-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
  }

  @Autowired private OpsEventsRepository repo;
  @Autowired private JdbcTemplate jdbc;

  // The methods share one tmp SQLite (the DB is per-class), so isolate each: start from an empty
  // ops_events instead of relying on JUnit's method-execution order.
  @BeforeEach
  void cleanSlate() {
    jdbc.update("DELETE FROM ops_events");
  }

  @Test
  void recordsAndReadsBackNewestFirst() {
    repo.record(OpsEventType.REGISTER, "pitch_outcome_pre v3.3 registered as SHADOW");
    repo.record(OpsEventType.PROMOTE, "pitch_outcome_pre v3.3 SHADOW → CHAMPION");
    repo.record(OpsEventType.ALERT, "PSI release_spin = 0.22");

    List<OpsEvent> recent = repo.findRecent(20);

    assertThat(recent).hasSize(3);
    // Newest first: the ALERT was written last.
    assertThat(recent.get(0).type()).isEqualTo(OpsEventType.ALERT);
    assertThat(recent.get(0).detail()).contains("release_spin");
    assertThat(recent)
        .extracting(OpsEvent::type)
        .containsExactly(OpsEventType.ALERT, OpsEventType.PROMOTE, OpsEventType.REGISTER);
    assertThat(recent.get(0).occurredAt()).isNotNull();
  }

  @Test
  void limitCapsRowsReturned() {
    for (int i = 0; i < 5; i++) {
      repo.record(OpsEventType.DEPLOY, "build deploy " + i);
    }
    assertThat(repo.findRecent(2)).hasSize(2);
  }

  @Test
  void occurredAtReadsAsUtc_notSkewedByEtJvmZone() {
    // Reproduce the prod box: a non-UTC (America/New_York) JVM default zone. occurred_at is written
    // UTC by SQLite CURRENT_TIMESTAMP; a bare getTimestamp() would reinterpret it in this zone and
    // shift it +4h. The tz-explicit read (JdbcTimes.utcInstant) must recover the real UTC instant -
    // i.e. land inside the write window, not ~4h in the future.
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
      Instant before = Instant.now().minusSeconds(30);
      repo.record(OpsEventType.DEPLOY, "tz skew check");
      Instant after = Instant.now().plusSeconds(30);

      Instant occurredAt = repo.findRecent(1).get(0).occurredAt();

      assertThat(occurredAt).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void findRecentPagePagesNewestFirstWithOverFetchHasNext() {
    for (int i = 0; i < 5; i++) {
      repo.record(OpsEventType.DEPLOY, "deploy " + i); // "deploy 4" is newest (highest id)
    }

    // Ordering is (occurred_at DESC, id DESC), so the id tiebreak keeps it deterministic even when
    // the five inserts share a CURRENT_TIMESTAMP second.
    PagedRows<OpsEvent> first = repo.findRecentPage(0, 2);
    assertThat(first.rows()).extracting(OpsEvent::detail).containsExactly("deploy 4", "deploy 3");
    assertThat(first.hasNext()).as("3 events remain past page 0").isTrue();

    PagedRows<OpsEvent> second = repo.findRecentPage(1, 2);
    assertThat(second.rows()).extracting(OpsEvent::detail).containsExactly("deploy 2", "deploy 1");
    assertThat(second.hasNext()).as("deploy 0 remains past page 1").isTrue();

    PagedRows<OpsEvent> last = repo.findRecentPage(2, 2);
    assertThat(last.rows()).extracting(OpsEvent::detail).containsExactly("deploy 0");
    assertThat(last.hasNext()).as("no events past the last page").isFalse();
  }
}
