package net.thebullpen.baseball.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.inference.ModelLoadValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the CANONICAL {@code /contracts} file for a model family (B1 / PR-3). Rule 7's intent is
 * "refuse models whose schema hash doesn't match the production feature pipeline" - before this
 * component, a first-ever (bootstrap) registration pinned whatever the caller submitted and {@code
 * /contracts} was never read on the Java side.
 *
 * <p>The family map mirrors what each trainer exports against (verified in the training code):
 *
 * <ul>
 *   <li>{@code pitch_outcome_pre} + {@code pitch_outcome_lr_baseline} - {@code
 *       feature_pipeline.json} ({@code register_pitch.py})
 *   <li>{@code pitch_outcome_post} - {@code feature_pipeline_post.json}
 *   <li>{@code pitch_type_pre} + {@code pitch_type_lr_baseline} - {@code
 *       feature_pipeline_pitchtype.json} (pre-pitch pitch-TYPE head, decision [183]; Phase 1a ships
 *       the contract before any model is registered)
 *   <li>{@code battedball_outcome} + {@code battedball_lgbm_per_park} + {@code
 *       lr_baseline_batted_ball} - {@code feature_pipeline_battedball.json} (both batted-ball
 *       exporters declare this CONTRACT_PATH; the per-park LGBM consumes the same 15-feature input
 *       contract as the MLP)
 *   <li>{@code battedball_lgbm_baseline} - {@code feature_pipeline_lgbm_battedball.json} (the
 *       Option-A flat baseline, not currently registered)
 * </ul>
 *
 * <p>A model name OUTSIDE the map has no canonical contract yet: {@link #canonicalHashFor} returns
 * empty and the registry pins the submitted hash exactly as before (logged loudly). Adding the
 * family here is the one-line change that arms the gate for it; {@code registerWithBootstrap} stays
 * the high-friction escape hatch for deliberate schema resets.
 *
 * <p>The contracts directory is {@code bullpen.registry.contracts-dir} (env {@code
 * BULLPEN_REGISTRY_CONTRACTSDIR} - Spring relaxed binding drops the hyphen). Dev default {@code
 * ../contracts} resolves from {@code backend/}; on the box the systemd units point it at {@code
 * /opt/bullpen/contracts}, which {@code deploy.sh} stages from the checkout on every deploy.
 */
@Component
public class CanonicalContracts {

  private static final Map<String, String> CONTRACT_FILE_BY_MODEL =
      Map.of(
          "pitch_outcome_pre", "feature_pipeline.json",
          "pitch_outcome_lr_baseline", "feature_pipeline.json",
          "pitch_outcome_post", "feature_pipeline_post.json",
          "pitch_type_pre", "feature_pipeline_pitchtype.json",
          "pitch_type_lr_baseline", "feature_pipeline_pitchtype.json",
          "battedball_outcome", "feature_pipeline_battedball.json",
          "battedball_lgbm_per_park", "feature_pipeline_battedball.json",
          "lr_baseline_batted_ball", "feature_pipeline_battedball.json",
          "battedball_lgbm_baseline", "feature_pipeline_lgbm_battedball.json");

  /**
   * The {@code model_kind} a family's {@code metadata.json} MUST declare (decision [184]), keyed by
   * the family's canonical contract file, which is what correctly arms the rule-9 BASELINE from the
   * same one-line entry as its primary (both map to {@code feature_pipeline_pitchtype.json}), so
   * the baseline cannot register kind-less while the primary is required to declare.
   *
   * <p>Do NOT read this as "any future family member inherits automatically". The pitch-OUTCOME
   * precedent in the map above shows the opposite: pre and post have DIFFERENT contract files
   * because post adds Tier-4 features. A {@code pitch_type_post} would likely get its own contract
   * and would register UNARMED unless added here. {@code CanonicalContractsTest} asserts every
   * {@code pitch_type*} name resolves to a kind, so forgetting reds a test instead of shipping.
   *
   * <p>ABSENT MEANS UNARMED, exactly as an unmapped contract file does for the schema-hash gate.
   * Every family registered before [184] is resolved by the load gate field-sniffing instead
   * ({@code park_order} for the batted-ball all-parks head, {@code head} for pitch-outcome), and
   * none of their metadata carries this field - so an unconditional requirement would refuse every
   * re-registration of the existing fleet (automated retraining, and the fresh-version path in
   * {@code docs/runbooks/registry-snapshot-recovery.md}). Arming a family is the one-line change
   * here, same idiom as the map above.
   */
  private static final Map<String, String> KIND_BY_CONTRACT_FILE =
      Map.of("feature_pipeline_pitchtype.json", ModelLoadValidator.PITCH_TYPE_KIND);

  private final Path contractsDir;
  private final FeatureSchemaHasher hasher;

  public CanonicalContracts(
      @Value("${bullpen.registry.contracts-dir:../contracts}") String contractsDir,
      FeatureSchemaHasher hasher) {
    this.contractsDir = Path.of(contractsDir).toAbsolutePath().normalize();
    this.hasher = hasher;
  }

  /** The canonical contract filename for {@code modelName}; empty for an unmapped family. */
  static Optional<String> contractFileFor(String modelName) {
    return Optional.ofNullable(CONTRACT_FILE_BY_MODEL.get(modelName));
  }

  /**
   * The {@code model_kind} {@code modelName}'s {@code metadata.json} must declare; empty when the
   * family predates decision [184] and is resolved by field-sniffing instead.
   *
   * <p>Derived from the contract-file table rather than a second model-name list, so family
   * membership is stated once. Static because it needs no contracts directory - it is a statement
   * about the artifact's own metadata, not about a file on disk.
   */
  public static Optional<String> requiredModelKindFor(String modelName) {
    return contractFileFor(modelName).map(KIND_BY_CONTRACT_FILE::get);
  }

  /**
   * The canonical schema hash for {@code modelName}'s family, computed with the SAME {@link
   * FeatureSchemaHasher} the registration check uses. Empty when the family has no mapped contract.
   * A mapped family whose contract file is MISSING on disk is a deployment defect (deploy.sh stages
   * {@code contracts/}; dev runs from the checkout) and fails loud rather than silently degrading
   * to pin-as-submitted.
   */
  public Optional<String> canonicalHashFor(String modelName) {
    Optional<String> file = contractFileFor(modelName);
    if (file.isEmpty()) {
      return Optional.empty();
    }
    Path canonical = contractsDir.resolve(file.get());
    if (!Files.isRegularFile(canonical)) {
      throw new RegistryException.ArtifactMissing(
          "canonical contract for "
              + modelName
              + " not found at "
              + canonical
              + " - set bullpen.registry.contracts-dir (env BULLPEN_REGISTRY_CONTRACTSDIR) to the"
              + " staged contracts directory; deploy.sh stages the checkout's contracts/ to"
              + " /opt/bullpen/contracts on the box");
    }
    return Optional.of(hasher.compute(canonical));
  }
}
