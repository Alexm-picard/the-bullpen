package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.OpsEvent;
import net.thebullpen.baseball.api.dto.OpsEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
}
