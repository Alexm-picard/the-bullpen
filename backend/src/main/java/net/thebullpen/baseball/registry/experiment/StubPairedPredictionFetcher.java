package net.thebullpen.baseball.registry.experiment;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link PairedPredictionFetcher} implementation — returns an empty list and logs a NOTICE.
 * The real ClickHouse-querying impl lands in 3c when drift detection needs the same
 * pivot-and-truth-join plumbing.
 *
 * <p>An empty list makes {@link ExperimentService#complete} refuse with {@code
 * InsufficientSampleSize} — which is the right behavior in prod-without-the-real-impl: an admin
 * can't accidentally mark an experiment "passed" against zero data.
 *
 * <p>{@link Primary} so Spring auto-wires this when no real implementation is provided. Tests
 * inject their own {@link PairedPredictionFetcher} bean via {@code @TestConfiguration} (preferred),
 * or call {@code ExperimentService} methods that take a fetcher explicitly.
 */
@Component
public class StubPairedPredictionFetcher implements PairedPredictionFetcher {

  private static final Logger log = LoggerFactory.getLogger(StubPairedPredictionFetcher.class);

  @Override
  public List<PairedPrediction> fetch(
      String modelName,
      String championVersion,
      String challengerVersion,
      Instant since,
      Instant until) {
    log.info(
        "PairedPredictionFetcher stub: returning empty list for {} ({} vs {}) in [{}, {}] —"
            + " real ClickHouse-joining impl lands in 3c",
        modelName,
        championVersion,
        challengerVersion,
        since,
        until);
    return List.of();
  }
}
