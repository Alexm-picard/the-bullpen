package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage (no ClickHouse) of the category-token normalization: JSONExtractRaw hands back raw
 * JSON tokens, and the map keys must line up with the training-side counts' string keys for both
 * string-valued and int-coded categoricals.
 */
class RealFeatureDistributionFetcherTest {

  @Test
  void strips_quotes_from_json_string_tokens() {
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("\"L\"")).isEqualTo("L");
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("\"BOS\"")).isEqualTo("BOS");
  }

  @Test
  void passes_bare_numeric_tokens_through_as_string_keys() {
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("7")).isEqualTo("7");
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("2.5")).isEqualTo("2.5");
  }

  @Test
  void rejects_null_empty_and_quoted_empty_tokens() {
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken(null)).isNull();
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("")).isNull();
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("null")).isNull();
    assertThat(RealFeatureDistributionFetcher.normalizeCategoryToken("\"\"")).isNull();
  }
}
