package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModelLoadValidator#assertCarrySane} - the Phase-4 carry load-gate hardening
 * ([166]). A carry-serving champion that mis-exports its carry head (NaN / zero / absurd feet) must
 * 422 at promote-time, not serve absurd carry on /parks. The INC-2 load gate previously validated
 * output[0] (probabilities) only.
 */
class ModelLoadValidatorCarryTest {

  private static Map<String, Double> carry(double... ft) {
    Map<String, Double> m = new LinkedHashMap<>();
    for (int i = 0; i < ft.length; i++) {
      m.put("P" + i, ft[i]);
    }
    return m;
  }

  @Test
  void accepts_plausible_per_park_carry() {
    assertThatCode(
            () -> ModelLoadValidator.assertCarrySane(carry(401.0, 439.0, 388.0, 423.0), "m/v2"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejects_missing_carry_output() {
    assertThatThrownBy(() -> ModelLoadValidator.assertCarrySane(null, "m/v2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no carry output");
    assertThatThrownBy(() -> ModelLoadValidator.assertCarrySane(new LinkedHashMap<>(), "m/v2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no carry output");
  }

  @Test
  void rejects_nan_carry() {
    assertThatThrownBy(() -> ModelLoadValidator.assertCarrySane(carry(401.0, Double.NaN), "m/v2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out of sane range");
  }

  @Test
  void rejects_out_of_range_carry() {
    // absurd high (a broken / un-standardised head), zero, and negative all fail the [50, 550]
    // band.
    assertThatThrownBy(() -> ModelLoadValidator.assertCarrySane(carry(9999.0), "m/v2"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out of sane range");
    assertThatThrownBy(() -> ModelLoadValidator.assertCarrySane(carry(0.0), "m/v2"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ModelLoadValidator.assertCarrySane(carry(-5.0), "m/v2"))
        .isInstanceOf(IllegalStateException.class);
  }
}
