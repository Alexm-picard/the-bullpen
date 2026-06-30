package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.registry.dto.PromotionEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

/**
 * Loads the committed accuracy artifacts bundled into the JAR (see {@code backend/build.gradle.kts}
 * {@code processResources}, which copies {@code training/data/eval/promotion/ *_full*.json} and the
 * box-produced {@code battedball_backfill_accuracy_v1.json} into {@code
 * classpath:accuracy-evidence/}).
 *
 * <p>Read once at construction and cached immutably - the files only change on deploy. Classpath
 * bundling means tests and prod read identically and no {@code deploy.sh} staging is needed. {@code
 * api} profile only (it backs a read endpoint).
 *
 * <p>The backfill artifact is the box-only hand-off (PR4): it is absent until the box runs the
 * scoring job and commits it, so a missing file is tolerated as {@link Optional#empty()} (the
 * endpoint then returns its honest empty state), not an error. A total absence of
 * promotion-evidence is logged loudly (a build-copy misconfiguration) but still degrades to an
 * empty scorecard rather than failing app boot.
 */
@Repository
@Profile("api")
// final: the constructor reads a bundled classpath resource and can throw (fail-fast), so SpotBugs
// flags CT_CONSTRUCTOR_THROW (finalizer-attack via a malicious subclass). This is a leaf bean,
// never subclassed; final closes that vector without restructuring the fail-fast load.
public final class AccuracyEvidenceRepository {

  private static final Logger log = LoggerFactory.getLogger(AccuracyEvidenceRepository.class);
  private static final String EVIDENCE_GLOB =
      "classpath*:accuracy-evidence/*_experiment_results_full*.json";
  private static final String BACKFILL_RESOURCE =
      "accuracy-evidence/battedball_backfill_accuracy_v1.json";

  private final List<PromotionEvidence> evidence;
  private final JsonNode backfill; // nullable until the box hand-off (PR4) commits the artifact

  public AccuracyEvidenceRepository(ObjectMapper mapper) {
    this.evidence = loadEvidence(mapper);
    this.backfill = loadBackfill(mapper);
  }

  /**
   * Promotion-evidence rows, de-duped to one per training model (per-park-isotonic variant wins).
   */
  public List<PromotionEvidence> evidence() {
    return evidence;
  }

  /** The box-produced batted-ball backfill artifact, verbatim, or empty until PR4 commits it. */
  public Optional<JsonNode> backfill() {
    return Optional.ofNullable(backfill);
  }

  private static List<PromotionEvidence> loadEvidence(ObjectMapper mapper) {
    Resource[] resources;
    try {
      resources = new PathMatchingResourcePatternResolver().getResources(EVIDENCE_GLOB);
    } catch (IOException e) {
      throw new UncheckedIOException("failed scanning classpath for accuracy evidence", e);
    }
    // De-dupe by training model_name; the batted-ball model ships two evidence files (plain +
    // per_park_isotonic) and the per-park-isotonic variant is the one production actually serves
    // (decision [163]). Keep that one; first-wins for any model with a single file.
    Map<String, PromotionEvidence> byModel = new LinkedHashMap<>();
    for (Resource r : resources) {
      PromotionEvidence ev;
      try {
        ev = mapper.readValue(r.getInputStream(), PromotionEvidence.class);
      } catch (IOException e) {
        throw new UncheckedIOException("failed parsing accuracy evidence " + r.getFilename(), e);
      }
      if (ev.modelName() == null) {
        log.warn("accuracy evidence {} has no model_name; skipping", r.getFilename());
        continue;
      }
      PromotionEvidence existing = byModel.get(ev.modelName());
      if (existing == null || isPerParkIsotonic(ev)) {
        byModel.put(ev.modelName(), ev);
      }
    }
    if (byModel.isEmpty()) {
      log.warn(
          "no promotion-evidence resources matched {} - the scorecard will be empty; check the"
              + " build.gradle.kts processResources copy",
          EVIDENCE_GLOB);
    }
    return List.copyOf(new ArrayList<>(byModel.values()));
  }

  private static boolean isPerParkIsotonic(PromotionEvidence ev) {
    return ev.provenance() != null && "per_park_isotonic".equals(ev.provenance().mlpCalibration());
  }

  private static JsonNode loadBackfill(ObjectMapper mapper) {
    ClassPathResource r = new ClassPathResource(BACKFILL_RESOURCE);
    if (!r.exists()) {
      log.info(
          "no batted-ball backfill artifact on the classpath yet ({}); the backfill endpoint will"
              + " report its empty state until the box hand-off commits it",
          BACKFILL_RESOURCE);
      return null;
    }
    try {
      return mapper.readTree(r.getInputStream());
    } catch (IOException e) {
      throw new UncheckedIOException("failed parsing backfill artifact " + BACKFILL_RESOURCE, e);
    }
  }
}
