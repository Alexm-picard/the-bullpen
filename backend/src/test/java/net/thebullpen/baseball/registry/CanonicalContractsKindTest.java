package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;

import net.thebullpen.baseball.inference.ModelLoadValidator;
import org.junit.jupiter.api.Test;

/**
 * Structural guards on decision [184]'s arming table.
 *
 * <p>The requirement is keyed by canonical CONTRACT FILE, which correctly arms the rule-9 baseline
 * from the same entry as its primary. It does NOT follow that a future family member inherits
 * automatically: the pitch-OUTCOME precedent shows pre and post carry different contract files
 * (post adds Tier-4), so a {@code pitch_type_post} would likely get its own contract and register
 * UNARMED. These tests make that mistake fail here rather than on the box.
 */
class CanonicalContractsKindTest {

  @Test
  void every_pitch_type_family_member_is_armed() {
    // The mistake this catches: adding pitch_type_post to CONTRACT_FILE_BY_MODEL with its own
    // contract file, and forgetting the corresponding KIND_BY_CONTRACT_FILE entry - which would
    // silently register the new head without the model_kind its loader is resolved by.
    assertThat(CanonicalContracts.requiredModelKindFor("pitch_type_pre"))
        .contains(ModelLoadValidator.PITCH_TYPE_KIND);
    assertThat(CanonicalContracts.requiredModelKindFor("pitch_type_lr_baseline"))
        .contains(ModelLoadValidator.PITCH_TYPE_KIND);
  }

  @Test
  void the_rule_9_baseline_is_armed_by_the_same_entry_as_its_primary() {
    // Rule 9's two rows must be equally trustworthy. Because arming keys on the shared contract
    // file, the baseline cannot be registered kind-less while the primary is required to declare.
    assertThat(CanonicalContracts.requiredModelKindFor("pitch_type_lr_baseline"))
        .isEqualTo(CanonicalContracts.requiredModelKindFor("pitch_type_pre"));
  }

  @Test
  void families_predating_the_decision_are_unarmed() {
    // Not an oversight, a requirement: none of these write model_kind, and arming them would
    // refuse every re-registration of the existing fleet (automated retraining, and the
    // fresh-version path in docs/runbooks/registry-snapshot-recovery.md).
    assertThat(CanonicalContracts.requiredModelKindFor("battedball_outcome")).isEmpty();
    assertThat(CanonicalContracts.requiredModelKindFor("pitch_outcome_pre")).isEmpty();
    assertThat(CanonicalContracts.requiredModelKindFor("pitch_outcome_post")).isEmpty();
  }

  @Test
  void an_unmapped_name_is_unarmed() {
    assertThat(CanonicalContracts.requiredModelKindFor("pitch_type_experimental")).isEmpty();
  }
}
