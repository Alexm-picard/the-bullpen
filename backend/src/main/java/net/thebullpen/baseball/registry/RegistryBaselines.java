package net.thebullpen.baseball.registry;

import java.util.Map;
import java.util.Optional;

/**
 * The rule-9 (B4) primary-head -&gt; co-registered LR baseline mapping - the SINGLE source of truth
 * for the pairing. Read by {@link RegistryService}'s baseline-presence gate ({@code
 * assertBaselineRegistered}) and by {@code OfflineGateImportService}'s first-champion binding (a
 * no-champion model binds its offline-gate row to this baseline).
 *
 * <p>Hardcoded (vs a {@code baseline_model_name} column) deliberately: the pairing is a design-time
 * fact from decision [37]/[46], the map is tiny, and it avoids a migration. Baseline model names
 * themselves are absent from the map, so baselines promote without self-reference.
 */
public final class RegistryBaselines {

  private RegistryBaselines() {}

  private static final Map<String, String> BASELINE_FOR_PRIMARY =
      Map.of(
          "pitch_outcome_pre", "pitch_outcome_lr_baseline",
          "pitch_outcome_post", "pitch_outcome_lr_baseline",
          // pitch-TYPE head (decision [183]): first champion promotes via the [182]/#334
          // first-champion offline-gate path, which binds championVersionId to this rule-9
          // baseline.
          "pitch_type_pre", "pitch_type_lr_baseline",
          "battedball_outcome", "lr_baseline_batted_ball",
          "battedball_lgbm_per_park", "lr_baseline_batted_ball");

  /**
   * The rule-9 baseline model name for a primary head, or empty if it has none (e.g. a baseline).
   */
  public static Optional<String> baselineFor(String primaryModelName) {
    return Optional.ofNullable(BASELINE_FOR_PRIMARY.get(primaryModelName));
  }
}
