package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drift-proofing for the E-4 ops read DTO: {@link TaggedDriftMetric} is a deliberate flat mirror of
 * {@link DriftMetric} plus the V027 {@code tag} (see its javadoc for why it is neither
 * {@code @JsonUnwrapped} nor a new component on {@code DriftMetric} itself). This guard fails
 * loudly if {@code DriftMetric} ever gains, renames, or reorders a component without the ops JSON
 * shape following - the mirror must never silently diverge (java-reviewer suggestion, E-4).
 */
class TaggedDriftMetricTest {

  @Test
  void mirrors_every_drift_metric_component_in_order_plus_the_trailing_tag() {
    List<String> base = componentNames(DriftMetric.class);
    List<String> tagged = componentNames(TaggedDriftMetric.class);

    assertThat(tagged.subList(0, tagged.size() - 1))
        .as("TaggedDriftMetric must mirror DriftMetric's components, same names, same order")
        .isEqualTo(base);
    assertThat(tagged.get(tagged.size() - 1)).isEqualTo("tag");

    // Types must mirror too, not just names.
    RecordComponent[] baseComponents = DriftMetric.class.getRecordComponents();
    RecordComponent[] taggedComponents = TaggedDriftMetric.class.getRecordComponents();
    for (int i = 0; i < baseComponents.length; i++) {
      assertThat(taggedComponents[i].getType())
          .as("component %s", baseComponents[i].getName())
          .isEqualTo(baseComponents[i].getType());
    }
  }

  @Test
  void normalizes_a_null_tag_to_empty() {
    Instant now = Instant.parse("2026-07-16T12:00:00Z");
    TaggedDriftMetric row =
        new TaggedDriftMetric(
            now,
            "battedball_outcome",
            1L,
            MetricType.PSI_FEATURE,
            "launchSpeedMph",
            0.3,
            100L,
            now.minusSeconds(86400),
            now,
            null);
    assertThat(row.tag()).isEmpty();
  }

  private static List<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }
}
