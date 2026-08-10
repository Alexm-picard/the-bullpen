package net.thebullpen.baseball.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-language parity for the pitch-outcome class vocabulary.
 *
 * <p>THE GAP. Two Java constants hardcode the 5-class order that must match Python's {@code
 * LABEL_CLASSES} in {@code training/src/bullpen_training/features/__init__.py}:
 *
 * <ul>
 *   <li>{@code ClickHouseTruthJoinedPredictionFetcher.OUTCOME_CLASSES} (drift truth-join)
 *   <li>{@code ClickHousePairedPredictionFetcher.OUTCOME_CLASSES} (experiment paired predictions)
 * </ul>
 *
 * <p>Both resolve truth labels by index ({@code indexOf(description)}), so a reordering silently
 * maps every truth to the wrong class. No in-language check can see the mismatch; only comparing
 * against the Python definition catches it.
 *
 * <p>EXPECTED VALUES FROM PYTHON, not re-derived. To regenerate:
 *
 * <pre>
 *   uv run python -c "from bullpen_training.features import LABEL_CLASSES; print(list(LABEL_CLASSES))"
 * </pre>
 */
class OutcomeClassParityTest {

  private static final List<String> FROM_PYTHON =
      List.of("ball", "called_strike", "swinging_strike", "foul", "in_play");

  @Test
  void truthJoinFetcherMatchesTrainingLabelClasses() throws Exception {
    List<String> actual =
        readOutcomeClasses("net.thebullpen.baseball.drift.ClickHouseTruthJoinedPredictionFetcher");
    assertThat(actual)
        .as(
            "drift truth-join class order must match training's LABEL_CLASSES"
                + " - a mismatch silently maps every truth to the wrong class")
        .containsExactlyElementsOf(FROM_PYTHON);
  }

  @Test
  void pairedPredictionFetcherMatchesTrainingLabelClasses() throws Exception {
    List<String> actual =
        readOutcomeClasses(
            "net.thebullpen.baseball.registry.experiment.ClickHousePairedPredictionFetcher");
    assertThat(actual)
        .as(
            "experiment paired-prediction class order must match training's LABEL_CLASSES"
                + " - a mismatch silently maps every truth to the wrong class")
        .containsExactlyElementsOf(FROM_PYTHON);
  }

  @SuppressWarnings("unchecked")
  private static List<String> readOutcomeClasses(String className) throws Exception {
    Class<?> clazz = Class.forName(className);
    Field field = clazz.getDeclaredField("OUTCOME_CLASSES");
    field.setAccessible(true);
    return (List<String>) field.get(null);
  }
}
