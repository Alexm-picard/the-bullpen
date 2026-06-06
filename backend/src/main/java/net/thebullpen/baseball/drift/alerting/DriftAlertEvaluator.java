package net.thebullpen.baseball.drift.alerting;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.registry.DiscordNotifier;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads {@link DriftMetricsRepository} + applies the threshold table from leaf 3c.7 / decision [78]
 * + fires Discord via {@link DiscordNotifier}. Runs daily at 3 AM ET, after the drift batches
 * (PsiFeature 2:00, PsiPrediction 2:10, Calibration 2:30) have written their rows.
 *
 * <p>Threshold table:
 *
 * <ul>
 *   <li>{@link AlertSeverity#PAGE}: champion {@link MetricType#CALIBRATION_ERROR} (segment="all")
 *       sustained &gt; {@code calibrationPageThreshold} (default 0.10) for 3+ consecutive days. The
 *       leaf body says "1.5× training calibration sustained 3 days" — we fall back to an absolute
 *       threshold per leaf "Known edge cases" because {@code training_calibration} is not yet
 *       emitted into {@code metadata.json}.
 *   <li>{@link AlertSeverity#NOTICE}: any {@link MetricType#PSI_FEATURE} value &gt; {@code
 *       featurePsiNoticeThreshold} (default 0.25) sustained 7+ consecutive days per feature.
 * </ul>
 *
 * <p>Dedup: each alert key is suppressed for 24h after fire (Discord-spam prevention).
 *
 * <p>{@code @ConditionalOnBean(DriftMetricsRepository)} so dev-without-ClickHouse brings the
 * context up without trying to wire this evaluator.
 */
@Component
@Profile("worker")
@ConditionalOnBean(DriftMetricsRepository.class)
public class DriftAlertEvaluator {

  private static final Logger log = LoggerFactory.getLogger(DriftAlertEvaluator.class);

  private static final Duration DEDUP_WINDOW = Duration.ofHours(24);
  private static final Duration PAGE_LOOKBACK = Duration.ofDays(3);
  private static final Duration NOTICE_LOOKBACK = Duration.ofDays(7);
  // "Consecutive days" is measured in calendar days in this zone (matches the 3 AM ET schedule).
  private static final ZoneId ALERT_ZONE = ZoneId.of("America/New_York");

  private final RegistryRepository registryRepo;
  private final DriftMetricsRepository driftRepo;
  private final AlertHistoryRepository historyRepo;
  private final DiscordNotifier discord;
  private final double calibrationPageThreshold;
  private final double featurePsiNoticeThreshold;

  public DriftAlertEvaluator(
      RegistryRepository registryRepo,
      DriftMetricsRepository driftRepo,
      AlertHistoryRepository historyRepo,
      DiscordNotifier discord,
      @Value("${bullpen.drift.alert.calibration-page-threshold:0.10}")
          double calibrationPageThreshold,
      @Value("${bullpen.drift.alert.feature-psi-notice-threshold:0.25}")
          double featurePsiNoticeThreshold) {
    this.registryRepo = registryRepo;
    this.driftRepo = driftRepo;
    this.historyRepo = historyRepo;
    this.discord = discord;
    this.calibrationPageThreshold = calibrationPageThreshold;
    this.featurePsiNoticeThreshold = featurePsiNoticeThreshold;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "America/New_York")
  public void evaluate() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.error("DriftAlertEvaluator: run failed", e);
    }
  }

  /** Visible-for-tests entry point. Returns number of alerts fired (excluding dedup-suppressed). */
  public int runOnce() {
    int fired = 0;
    for (ModelVersion champ : activeChampions()) {
      fired += evaluateCalibration(champ);
      fired += evaluateFeaturePsi(champ);
    }
    if (fired > 0) {
      log.info("DriftAlertEvaluator: fired {} alert(s) this run", fired);
    }
    return fired;
  }

  private int evaluateCalibration(ModelVersion champ) {
    List<DriftMetric> recent =
        driftRepo.findRecent(champ.modelName(), MetricType.CALIBRATION_ERROR, "all", PAGE_LOOKBACK);
    // Collapse to one canonical value per calendar day before counting "days": a same-day rerun of
    // the 2:30 calibration batch writes multiple rows, and counting rows would let 3 reruns on one
    // day masquerade as 3 consecutive days and fire a false PAGE (DEF-M3). Latest sample wins per
    // day (reruns supersede). 3 distinct days within the 3-day lookback ARE consecutive.
    List<Double> daily = dailyCanonical(recent);
    if (daily.size() < 3) {
      return 0;
    }
    boolean allOver = daily.stream().allMatch(v -> v > calibrationPageThreshold);
    if (!allOver) {
      return 0;
    }
    String key = "drift/" + champ.modelName() + "/calibration_error/all";
    if (historyRepo.firedWithin(key, DEDUP_WINDOW)) {
      log.debug("DriftAlertEvaluator: PAGE for {} suppressed by 24h dedup", key);
      return 0;
    }
    double worst = daily.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    discord.send(
        DiscordNotifier.Severity.WARN,
        "PAGE: " + champ.modelName() + " calibration drifted",
        Map.of(
            "metric_type",
            "CALIBRATION_ERROR",
            "worst_value",
            worst,
            "threshold",
            calibrationPageThreshold,
            "consecutive_days",
            daily.size(),
            "runbook",
            "docs/runbooks/calibration-drift-investigation.md"));
    historyRepo.record(
        key,
        AlertSeverity.PAGE,
        worst,
        calibrationPageThreshold,
        "Sustained calibration drift for " + daily.size() + " days");
    return 1;
  }

  private int evaluateFeaturePsi(ModelVersion champ) {
    int fired = 0;
    // Group recent PSI_FEATURE rows by feature_or_segment then check sustained-over-threshold.
    List<DriftMetric> recent =
        driftRepo.findRecent(champ.modelName(), MetricType.PSI_FEATURE, "", NOTICE_LOOKBACK);
    // The repository's findRecent filters by exact featureOrSegment value — querying "" matches
    // only rows that intentionally use empty segment, which would NEVER match PSI_FEATURE rows
    // (those carry the feature name). For per-feature evaluation we need a different lookup;
    // we use findAllForModel + filter in memory. At the volumes 3c.2 writes (~30 features × 7
    // days = 210 rows per model per week), the in-memory filter is fine.
    List<DriftMetric> psiRows =
        driftRepo.findAllForModel(champ.modelName()).stream()
            .filter(m -> m.metricType() == MetricType.PSI_FEATURE)
            .filter(m -> m.computedAt().isAfter(java.time.Instant.now().minus(NOTICE_LOOKBACK)))
            .toList();
    if (psiRows.isEmpty()) {
      return 0;
    }
    java.util.Map<String, java.util.List<DriftMetric>> byFeature = new java.util.HashMap<>();
    for (DriftMetric m : psiRows) {
      byFeature.computeIfAbsent(m.featureOrSegment(), k -> new ArrayList<>()).add(m);
    }
    for (var entry : byFeature.entrySet()) {
      String feature = entry.getKey();
      // Same calendar-day collapse as calibration: 7 distinct days over threshold, not 7 rows
      // (a same-day PSI rerun must not count twice toward the 7-day sustain) (DEF-M3).
      List<Double> daily = dailyCanonical(entry.getValue());
      if (daily.size() < 7) {
        continue;
      }
      if (!daily.stream().allMatch(v -> v > featurePsiNoticeThreshold)) {
        continue;
      }
      String key = "drift/" + champ.modelName() + "/psi_feature/" + feature;
      if (historyRepo.firedWithin(key, DEDUP_WINDOW)) {
        log.debug("DriftAlertEvaluator: NOTICE for {} suppressed by 24h dedup", key);
        continue;
      }
      double worst = daily.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
      discord.send(
          DiscordNotifier.Severity.NOTICE,
          "NOTICE: " + champ.modelName() + " feature drift on " + feature,
          Map.of(
              "metric_type",
              "PSI_FEATURE",
              "feature",
              feature,
              "worst_value",
              worst,
              "threshold",
              featurePsiNoticeThreshold,
              "consecutive_days",
              daily.size(),
              "runbook",
              "docs/runbooks/feature-drift-investigation.md"));
      historyRepo.record(
          key,
          AlertSeverity.NOTICE,
          worst,
          featurePsiNoticeThreshold,
          "Sustained PSI drift on " + feature + " for " + daily.size() + " days");
      fired++;
    }
    // Suppress the unused-variable warning on the original 7-day exact-segment query.
    if (!recent.isEmpty()) {
      log.trace(
          "DriftAlertEvaluator: empty-segment PSI lookup returned {} rows (expected 0)",
          recent.size());
    }
    return fired;
  }

  /**
   * Collapse drift rows to one canonical value per calendar day (in {@link #ALERT_ZONE}), the
   * latest sample winning so a same-day rerun supersedes rather than double-counts. Ordered
   * ascending by day, so "N values within an N-day lookback" correctly means N consecutive days
   * (DEF-M3).
   */
  private static List<Double> dailyCanonical(List<DriftMetric> rows) {
    Map<LocalDate, DriftMetric> latestPerDay = new HashMap<>();
    for (DriftMetric m : rows) {
      LocalDate day = m.computedAt().atZone(ALERT_ZONE).toLocalDate();
      DriftMetric cur = latestPerDay.get(day);
      if (cur == null || m.computedAt().isAfter(cur.computedAt())) {
        latestPerDay.put(day, m);
      }
    }
    return latestPerDay.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getValue().metricValue())
        .toList();
  }

  private List<ModelVersion> activeChampions() {
    List<String> seen = new ArrayList<>();
    List<ModelVersion> out = new ArrayList<>();
    for (String[] pair : registryRepo.findAllNameVersionPairs()) {
      if (!seen.contains(pair[0])) {
        seen.add(pair[0]);
      }
    }
    for (String name : seen) {
      for (ModelVersion v : registryRepo.findByName(name)) {
        if (v.stage() == Stage.CHAMPION) {
          out.add(v);
          break;
        }
      }
    }
    return out;
  }
}
