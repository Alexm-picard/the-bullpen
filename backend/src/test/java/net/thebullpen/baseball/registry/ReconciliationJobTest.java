package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReconciliationJob#detectOrphans(List, List)}. The Spring-wired schedule +
 * ClickHouse query path is exercised in prod only — there's no IT for it in 3a.5 because the
 * orchestration is trivial (one query + this helper + one log/Discord call) and standing up
 * ClickHouse Testcontainers just to exercise an INTERVAL DAY query would be heavy for the value.
 */
class ReconciliationJobTest {

  @Test
  void no_orphans_when_seen_is_subset_of_known() {
    List<String[]> known =
        List.of(
            new String[] {"pitch_outcome_pre", "v1"},
            new String[] {"pitch_outcome_pre", "v2"},
            new String[] {"batted_ball", "v1"});
    List<String[]> seen =
        List.of(new String[] {"pitch_outcome_pre", "v1"}, new String[] {"pitch_outcome_pre", "v2"});

    assertThat(ReconciliationJob.detectOrphans(known, seen)).isEmpty();
  }

  @Test
  void single_orphan_is_returned() {
    List<String[]> known = List.<String[]>of(new String[] {"pitch_outcome_pre", "v1"});
    List<String[]> seen =
        List.of(
            new String[] {"pitch_outcome_pre", "v1"}, new String[] {"pitch_outcome_pre", "v99"});

    List<String[]> orphans = ReconciliationJob.detectOrphans(known, seen);
    assertThat(orphans).hasSize(1);
    assertThat(orphans.get(0)).containsExactly("pitch_outcome_pre", "v99");
  }

  @Test
  void multiple_orphans_are_returned_in_order() {
    List<String[]> known = List.of();
    List<String[]> seen =
        List.of(
            new String[] {"model_a", "v1"},
            new String[] {"model_b", "v2"},
            new String[] {"model_c", "v3"});

    List<String[]> orphans = ReconciliationJob.detectOrphans(known, seen);
    assertThat(orphans).hasSize(3);
    assertThat(orphans)
        .extracting(arr -> arr[0] + "/" + arr[1])
        .containsExactly("model_a/v1", "model_b/v2", "model_c/v3");
  }

  @Test
  void duplicate_orphans_in_seen_are_only_reported_once() {
    // prediction_log DISTINCT should remove dupes upstream but the helper guards anyway.
    List<String[]> known = List.of();
    List<String[]> seen =
        List.of(
            new String[] {"model_a", "v1"},
            new String[] {"model_a", "v1"},
            new String[] {"model_a", "v1"});

    assertThat(ReconciliationJob.detectOrphans(known, seen)).hasSize(1);
  }

  @Test
  void name_collision_with_version_doesnt_create_false_orphan() {
    // Without a delimiter, "ab" + "c" would equal "a" + "bc". The slash delimiter prevents this.
    List<String[]> known = List.of(new String[] {"ab", "c"}, new String[] {"a", "bc"});
    List<String[]> seen = List.of(new String[] {"ab", "c"}, new String[] {"a", "bc"});

    assertThat(ReconciliationJob.detectOrphans(known, seen)).isEmpty();
  }

  @Test
  void empty_inputs_are_empty_output() {
    assertThat(ReconciliationJob.detectOrphans(List.of(), List.of())).isEmpty();
  }
}
