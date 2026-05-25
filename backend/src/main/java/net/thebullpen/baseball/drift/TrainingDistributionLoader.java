package net.thebullpen.baseball.drift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads training-time reference distributions from a registered model's {@code metadata.json}.
 * Cached per-{@code modelVersionId} so repeated reads (across a daily run that touches all of a
 * model's features) hit the disk once.
 *
 * <p>{@code metadata.json} is expected to carry a {@code feature_distributions} top-level key
 * mapping feature-name → distribution descriptor. Two descriptor shapes are recognized (3c.2 "Known
 * edge cases"):
 *
 * <pre>
 *   "feature_distributions": {
 *     "launch_speed_mph": { "kind": "continuous", "sample": [88.1, 90.3, ...] },
 *     "park_id":          { "kind": "categorical", "counts": { "NYY": 1234, "BOS": 1100, ... } }
 *   }
 * </pre>
 *
 * <p>When the key is absent, the loader returns empty distributions — the job logs at INFO and
 * skips PSI for that feature. This is the realistic state until training pipelines start emitting
 * distributions; today's toy {@code metadata.json} doesn't carry them.
 */
@Component
public class TrainingDistributionLoader {

  private static final Logger log = LoggerFactory.getLogger(TrainingDistributionLoader.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Per-{@code versionId} cache. ConcurrentHashMap so the daily job can fan out across models. */
  private final Map<Long, ReferenceDistributions> cache = new ConcurrentHashMap<>();

  /**
   * Load (or read-cached) the reference distributions for the given registered model version.
   * {@code metadataPath} comes from {@code model_versions.metadata_path}.
   */
  public ReferenceDistributions load(long versionId, Path metadataPath) {
    return cache.computeIfAbsent(versionId, id -> loadFresh(id, metadataPath));
  }

  private ReferenceDistributions loadFresh(long versionId, Path metadataPath) {
    if (!Files.exists(metadataPath)) {
      log.warn(
          "TrainingDistributionLoader: metadata.json missing for version {} at {} — empty distributions",
          versionId,
          metadataPath);
      return ReferenceDistributions.empty();
    }
    try {
      JsonNode root = MAPPER.readTree(metadataPath.toFile());
      JsonNode distNode = root.path("feature_distributions");
      if (distNode.isMissingNode() || distNode.isNull()) {
        log.info(
            "TrainingDistributionLoader: version {} has no feature_distributions block in {} —"
                + " PSI cannot be computed for this version until the trainer emits one",
            versionId,
            metadataPath);
        return ReferenceDistributions.empty();
      }
      Map<String, double[]> continuous = new HashMap<>();
      Map<String, Map<String, Integer>> categorical = new HashMap<>();
      distNode
          .fields()
          .forEachRemaining(
              entry -> {
                String featureName = entry.getKey();
                JsonNode spec = entry.getValue();
                String kind = spec.path("kind").asText("");
                if ("continuous".equals(kind) && spec.path("sample").isArray()) {
                  List<Double> sample = new ArrayList<>();
                  spec.path("sample").forEach(v -> sample.add(v.asDouble()));
                  double[] arr = new double[sample.size()];
                  for (int i = 0; i < sample.size(); i++) {
                    arr[i] = sample.get(i);
                  }
                  continuous.put(featureName, arr);
                } else if ("categorical".equals(kind) && spec.path("counts").isObject()) {
                  Map<String, Integer> counts = new HashMap<>();
                  spec.path("counts")
                      .fields()
                      .forEachRemaining(c -> counts.put(c.getKey(), c.getValue().asInt()));
                  categorical.put(featureName, counts);
                } else {
                  log.warn(
                      "TrainingDistributionLoader: feature {} has unknown kind={} in version {} —"
                          + " skipping",
                      featureName,
                      kind,
                      versionId);
                }
              });
      log.info(
          "TrainingDistributionLoader: version {} loaded {} continuous + {} categorical features",
          versionId,
          continuous.size(),
          categorical.size());
      return new ReferenceDistributions(continuous, categorical);
    } catch (IOException e) {
      log.warn(
          "TrainingDistributionLoader: could not read {} for version {} — empty distributions",
          metadataPath,
          versionId,
          e);
      return ReferenceDistributions.empty();
    }
  }

  /** Test hook: forget cached distributions for a version. */
  public void invalidate(long versionId) {
    cache.remove(versionId);
    predictionCache.remove(versionId);
  }

  // --- per-class prediction reference (3c.3) ---------------------------

  private final Map<Long, Map<String, double[]>> predictionCache = new ConcurrentHashMap<>();

  /**
   * Read the {@code training_prediction_distribution} block from {@code metadata.json}:
   *
   * <pre>
   *   "training_prediction_distribution": {
   *     "ball":          [0.30, 0.31, 0.28, ...],
   *     "called_strike": [0.20, 0.18, 0.21, ...],
   *     ...
   *   }
   * </pre>
   *
   * <p>Returns class-name → reference sample. Empty map if absent (training pipeline hasn't emitted
   * the block yet — leaf "Known edge cases" graceful degradation).
   */
  public Map<String, double[]> loadPerClassPredictionReference(long versionId, Path metadataPath) {
    return predictionCache.computeIfAbsent(versionId, id -> loadPerClassFresh(id, metadataPath));
  }

  private Map<String, double[]> loadPerClassFresh(long versionId, Path metadataPath) {
    if (!Files.exists(metadataPath)) {
      log.warn(
          "TrainingDistributionLoader: metadata.json missing for version {} at {} — empty per-class refs",
          versionId,
          metadataPath);
      return Map.of();
    }
    try {
      JsonNode root = MAPPER.readTree(metadataPath.toFile());
      JsonNode predNode = root.path("training_prediction_distribution");
      if (predNode.isMissingNode() || predNode.isNull() || !predNode.isObject()) {
        log.info(
            "TrainingDistributionLoader: version {} has no training_prediction_distribution block"
                + " in {} — PSI-prediction cannot be computed for this version",
            versionId,
            metadataPath);
        return Map.of();
      }
      Map<String, double[]> out = new HashMap<>();
      predNode
          .fields()
          .forEachRemaining(
              entry -> {
                if (!entry.getValue().isArray()) {
                  return;
                }
                List<Double> sample = new ArrayList<>();
                entry.getValue().forEach(v -> sample.add(v.asDouble()));
                double[] arr = new double[sample.size()];
                for (int i = 0; i < sample.size(); i++) {
                  arr[i] = sample.get(i);
                }
                out.put(entry.getKey(), arr);
              });
      log.info(
          "TrainingDistributionLoader: version {} loaded {} class reference distributions",
          versionId,
          out.size());
      return out;
    } catch (IOException e) {
      log.warn(
          "TrainingDistributionLoader: could not read training_prediction_distribution from {} for"
              + " version {}",
          metadataPath,
          versionId,
          e);
      return Map.of();
    }
  }

  /** Result type: reference distributions partitioned by kind. */
  public record ReferenceDistributions(
      Map<String, double[]> continuous, Map<String, Map<String, Integer>> categorical) {

    public static ReferenceDistributions empty() {
      return new ReferenceDistributions(Map.of(), Map.of());
    }

    public boolean isEmpty() {
      return continuous.isEmpty() && categorical.isEmpty();
    }
  }
}
