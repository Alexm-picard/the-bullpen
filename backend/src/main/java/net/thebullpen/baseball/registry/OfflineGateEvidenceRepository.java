package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.registry.dto.OfflineGateEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

/**
 * Loads committed OFFLINE promotion-gate artifacts bundled into the JAR (see {@code
 * backend/build.gradle.kts} {@code processResources}, which copies {@code
 * training/data/eval/promotion/ *_promotion_gate.json} into {@code
 * classpath:offline-gate-evidence/}).
 *
 * <p>Read once at construction, cached immutably (the files only change on deploy). Keyed by the
 * artifact FILENAME, so the {@code import-offline} admin endpoint can ONLY ingest a committed,
 * reviewed, DEPLOYED artifact - an operator cannot inject arbitrary "passed" JSON (which would make
 * the rule-5 gate theatre). Deliberately a SEPARATE classpath dir from {@code accuracy-evidence/}
 * so {@link AccuracyEvidenceRepository}'s {@code *_experiment_results_full*.json} glob never sees
 * these (an offline-gate row is raw-softmax promotion evidence, not a public {@code /accuracy}
 * scorecard row). {@code api} profile only (it backs the admin import endpoint).
 */
@Repository
@Profile("api")
public class OfflineGateEvidenceRepository {

  private static final Logger log = LoggerFactory.getLogger(OfflineGateEvidenceRepository.class);
  private static final String GLOB = "classpath*:offline-gate-evidence/*.json";

  private final Map<String, OfflineGateEvidence> byArtifact;

  public OfflineGateEvidenceRepository(ObjectMapper mapper) {
    this.byArtifact = load(mapper);
  }

  /** The committed offline-gate evidence with this filename, or empty if it is not bundled. */
  public Optional<OfflineGateEvidence> byArtifact(String artifactName) {
    return Optional.ofNullable(byArtifact.get(artifactName));
  }

  private static Map<String, OfflineGateEvidence> load(ObjectMapper mapper) {
    Resource[] resources;
    try {
      resources = new PathMatchingResourcePatternResolver().getResources(GLOB);
    } catch (IOException e) {
      throw new UncheckedIOException("failed scanning classpath for offline-gate evidence", e);
    }
    Map<String, OfflineGateEvidence> out = new LinkedHashMap<>();
    for (Resource r : resources) {
      String name = r.getFilename();
      if (name == null) {
        continue;
      }
      try {
        out.put(name, mapper.readValue(r.getInputStream(), OfflineGateEvidence.class));
      } catch (IOException e) {
        throw new UncheckedIOException("failed parsing offline-gate evidence " + name, e);
      }
    }
    if (out.isEmpty()) {
      log.info("no offline-gate evidence on the classpath ({}); import-offline will 404", GLOB);
    } else {
      log.info("loaded {} offline-gate evidence artifact(s): {}", out.size(), out.keySet());
    }
    return Map.copyOf(out);
  }
}
