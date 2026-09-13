package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the RFC 6902 JSON Patch applier. Each test builds a minimal GUMBO document,
 * applies one or more patch ops, and verifies the in-memory document state. The parser is real
 * ({@link MlbFeedParser}) so the returned {@link LiveGameFeed} is fully parsed, not a mock.
 */
class DiffPatchApplierTest {

  private static final String MINIMAL_GUMBO =
      """
      {
        "gamePk": 999001,
        "metaData": {"timeStamp": "20260912_220000"},
        "gameData": {
          "game": {"pk": 999001},
          "datetime": {"officialDate": "2026-09-12"},
          "status": {"detailedState": "In Progress"},
          "teams": {
            "home": {"id": 111, "abbreviation": "BOS"},
            "away": {"id": 147, "abbreviation": "NYY"}
          }
        },
        "liveData": {
          "plays": {
            "allPlays": [
              {
                "about": {"atBatIndex": 0, "halfInning": "top", "isTopInning": true, "inning": 1},
                "matchup": {
                  "pitcher": {"id": 545361},
                  "batter": {"id": 660271},
                  "pitchHand": {"code": "R"},
                  "batSide": {"code": "L"},
                  "postOnFirst": null, "postOnSecond": null, "postOnThird": null
                },
                "count": {"outs": 0},
                "result": {"homeScore": 0, "awayScore": 0},
                "playEvents": [
                  {
                    "isPitch": true,
                    "pitchNumber": 1,
                    "details": {"call": {"code": "B"}, "isInPlay": false},
                    "pitchData": {"startSpeed": 94.3, "coordinates": {"pX": 0.1, "pZ": 2.6}, "breaks": {}},
                    "count": {"balls": 1, "strikes": 0}
                  }
                ]
              }
            ],
            "currentPlay": {
              "about": {"atBatIndex": 0, "inning": 1, "isTopInning": true, "isComplete": false},
              "matchup": {
                "pitcher": {"id": 545361}, "batter": {"id": 660271},
                "pitchHand": {"code": "R"}, "batSide": {"code": "L"}
              },
              "count": {"balls": 1, "strikes": 0, "outs": 0},
              "playEvents": [
                {"isPitch": true, "pitchNumber": 1, "details": {"call": {"code": "B"}, "isInPlay": false},
                 "pitchData": {"startSpeed": 94.3, "coordinates": {"pX": 0.1, "pZ": 2.6}, "breaks": {}},
                 "count": {"balls": 1, "strikes": 0}}
              ]
            }
          }
        }
      }
      """;

  private ObjectMapper mapper;
  private MlbFeedParser parser;
  private DiffPatchApplier applier;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    parser = new MlbFeedParser(mapper);
    applier = new DiffPatchApplier(mapper, parser);
  }

  @Test
  void loadFull_initializes_the_applier_and_extracts_the_timecode() throws IOException {
    assertThat(applier.isInitialized()).isFalse();

    LiveGameFeed feed = applier.loadFull(MINIMAL_GUMBO);

    assertThat(applier.isInitialized()).isTrue();
    assertThat(applier.lastTimecode()).isEqualTo("20260912_220000");
    assertThat(feed.gamePk()).isEqualTo(999001L);
    assertThat(feed.pitches()).hasSize(1);
    assertThat(feed.pitches().get(0).description()).isEqualTo("ball");
  }

  @Test
  void applyDiff_with_replace_op_modifies_the_document() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "replace", "path": "/gameData/status/detailedState", "value": "Final"},
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220005"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);

    assertThat(result).isNotNull();
    assertThat(result.feed().status().name()).isEqualTo("COMPLETED");
    assertThat(applier.lastTimecode()).isEqualTo("20260912_220005");
  }

  @Test
  void applyDiff_with_add_op_adds_a_new_key_to_an_object() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "add", "path": "/gameData/teams/home/name", "value": "Boston Red Sox"},
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220003"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(applier.lastTimecode()).isEqualTo("20260912_220003");
  }

  @Test
  void applyDiff_with_remove_op_removes_an_existing_field() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "remove", "path": "/gameData/teams/away"},
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220003"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(result.feed().awayAbbrev())
        .as("the away team node was removed, so abbreviation should be null")
        .isNull();
  }

  @Test
  void applyDiff_with_copy_op_copies_within_the_document() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "copy", "from": "/gameData/teams/home/abbreviation", "path": "/gameData/teams/away/abbreviation"},
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220003"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(result.feed().awayAbbrev()).isEqualTo("BOS");
  }

  @Test
  void applyDiff_returns_null_for_a_non_array_response() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    DiffPatchApplier.ApplyResult result = applier.applyDiff(MINIMAL_GUMBO);

    assertThat(result)
        .as("a non-array response is the resync signal; the caller should use loadFull instead")
        .isNull();
  }

  @Test
  void applyDiff_throws_PatchFailedException_for_an_unknown_op() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [{"op": "move", "path": "/gameData/status", "from": "/gameData/venue"}]}]
        """;

    assertThatThrownBy(() -> applier.applyDiff(diff))
        .isInstanceOf(DiffPatchApplier.PatchFailedException.class)
        .hasMessageContaining("unknown patch op: move");
  }

  @Test
  void applyDiff_throws_PatchFailedException_on_a_timecode_gap_exceeding_60s() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_222200"}
        ]}]
        """;

    assertThatThrownBy(() -> applier.applyDiff(diff))
        .isInstanceOf(DiffPatchApplier.PatchFailedException.class)
        .hasMessageContaining("timecode gap");
  }

  @Test
  void applyDiff_accepts_a_timecode_advance_within_60s() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220030"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(applier.lastTimecode()).isEqualTo("20260912_220030");
  }

  @Test
  void applyDiff_tracks_touched_play_indices() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [
          {"op": "replace", "path": "/liveData/plays/allPlays/0/count/outs", "value": 1},
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220005"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(result.touchedPlayIndices()).containsExactly(0);
  }

  @Test
  void applyDiff_skips_diff_entries_without_a_diff_array() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"metadata": "this entry has no diff key"},
         {"diff": [{"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220005"}]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(applier.lastTimecode()).isEqualTo("20260912_220005");
  }

  @Test
  void applyDiff_with_add_appends_to_array_via_dash_index() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String newPlayEvent =
        """
        {"isPitch": true, "pitchNumber": 2,
         "details": {"call": {"code": "S"}, "isInPlay": false},
         "pitchData": {"startSpeed": 88.0, "coordinates": {"pX": -0.3, "pZ": 3.0}, "breaks": {}},
         "count": {"balls": 1, "strikes": 1}}
        """;
    String diff =
        """
        [{"diff": [
          {"op": "add", "path": "/liveData/plays/allPlays/0/playEvents/-", "value": %s},
          {"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220005"}
        ]}]
        """
            .formatted(newPlayEvent);

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(result.feed().pitches()).hasSize(2);
    assertThat(result.feed().pitches().get(1).description()).isEqualTo("swinging_strike");
  }

  @Test
  void applyDiff_throws_for_a_bad_path() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [{"op": "replace", "path": "no_leading_slash", "value": 1}]}]
        """;

    assertThatThrownBy(() -> applier.applyDiff(diff))
        .isInstanceOf(DiffPatchApplier.PatchFailedException.class)
        .hasMessageContaining("invalid JSON pointer");
  }

  @Test
  void isConsistencyCheckDue_is_false_immediately_after_loadFull() throws IOException {
    applier.loadFull(MINIMAL_GUMBO);
    assertThat(applier.isConsistencyCheckDue()).isFalse();
  }

  @Test
  void loadFull_after_an_applyDiff_resets_the_consistency_timer() throws Exception {
    applier.loadFull(MINIMAL_GUMBO);

    String diff =
        """
        [{"diff": [{"op": "replace", "path": "/metaData/timeStamp", "value": "20260912_220005"}]}]
        """;
    applier.applyDiff(diff);

    applier.loadFull(MINIMAL_GUMBO);
    assertThat(applier.isConsistencyCheckDue()).isFalse();
  }

  @Test
  void the_full_fixture_round_trips_through_loadFull_and_produces_pitches() throws Exception {
    String fullFixture = fixture("/mlb/feed_live_824753.json");

    LiveGameFeed feed = applier.loadFull(fullFixture);

    assertThat(feed.gamePk()).isEqualTo(824753L);
    assertThat(feed.pitches()).hasSize(300);
    assertThat(applier.isInitialized()).isTrue();
  }

  @Test
  void applyDiff_on_the_full_fixture_with_a_status_change_produces_the_new_status()
      throws Exception {
    String fullFixture = fixture("/mlb/feed_live_824753.json");
    applier.loadFull(fullFixture);
    String oldTimecode = applier.lastTimecode();

    String diff =
        """
        [{"diff": [
          {"op": "replace", "path": "/gameData/status/detailedState", "value": "Game Over"}
        ]}]
        """;

    DiffPatchApplier.ApplyResult result = applier.applyDiff(diff);
    assertThat(result).isNotNull();
    assertThat(result.feed().status().name()).isEqualTo("COMPLETED");
    assertThat(result.feed().pitches()).hasSize(300);
    assertThat(applier.lastTimecode())
        .as("unchanged when the diff does not touch metaData.timeStamp")
        .isEqualTo(oldTimecode);
  }

  private static String fixture(String path) throws IOException {
    try (var in = DiffPatchApplierTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing fixture " + path);
      }
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }
}
