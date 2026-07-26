package net.thebullpen.baseball.data;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Derives the pitch-type model's Tier-ARS features at serve time, reproducing exactly what {@code
 * compute_pitch_type_arsenal.sql} computes at training time.
 *
 * <p>THE DEFINITION IS CAREER-EXPANDING, NOT WINDOWED. Every ARS value is the pitcher's frequency
 * of a class over his WHOLE career strictly before the current pitch. The pitch-OUTCOME family's
 * {@code compute_tier3.sql} uses a 90-day warm-up floor and is the in-repo precedent anyone would
 * reach for; reusing that shape here diverges from training on every row while still producing
 * plausible numbers. {@code PitcherArsenalRepository} is likewise NOT a windowing variant of this -
 * it computes a different quantity for a frontend card.
 *
 * <p>WHY THIS IS A SNAPSHOT PLUS A DELTA RATHER THAN A QUERY. Measured: a live career-expanding
 * query reads 100% of {@code pitches} (7,442,393 rows for a single pitcher) because {@code
 * pitcher_id} is in neither the sort key nor any skip index. See V030's header.
 *
 * <p>The composition is EXACT rather than approximate because the snapshot stores COUNTS: {@code
 * ars_c = (base_n_c + delta_n_c) / (base_prior_n + delta_prior_n)}, NaN when the denominator is
 * zero (a pitcher's first career pitch - a declared production state that LightGBM handles
 * natively, ~0.17% of training rows).
 *
 * <p>WHY THIS LIVES IN {@code data/} AND NOT {@code inference/}. ArchUnit's {@code
 * inferenceMustNotDependOnPersistenceExceptItsOwnRepository} states the boundary plainly: serving
 * reads models and routing, not application tables, and it names its single exemption explicitly so
 * there is exactly one. This class composes two ClickHouse reads over application tables, so it is
 * a data-access concern that the serving path consumes - not a serving concern that reaches into
 * data. Moving it under {@code inference/} reds that rule immediately, which is how I found out.
 *
 * <p>TWO PRECONDITIONS ARE ENFORCED HERE RATHER THAN DOCUMENTED, because both fail SILENTLY and
 * both produce a confidently wrong prior. For a model whose whole purpose is calibration (decision
 * [183]), a quietly wrong prior is worse than no prediction at all.
 */
@Component
public class PitchTypeArsenalDeriver {

  private final PitcherPitchTypePriorRepository priors;
  private final int maxSnapshotAgeDays;

  public PitchTypeArsenalDeriver(
      PitcherPitchTypePriorRepository priors,
      @Value("${bullpen.pitchtype.max-snapshot-age-days:2}") int maxSnapshotAgeDays) {
    this.priors = priors;
    this.maxSnapshotAgeDays = maxSnapshotAgeDays;
  }

  /** A pitcher has no snapshot row at all, so no career prior can be composed. */
  public static final class PriorUnavailable extends RuntimeException {
    public PriorUnavailable(String message) {
      super(message);
    }
  }

  /**
   * The seven career-expanding class frequencies plus the by-count FF frequency, in canonical y7
   * order, for the pitch about to be thrown. A {@code null} element means "no prior" (the
   * denominator was zero) and serialises to NaN in the feature vector, exactly as training emits
   * NULL there.
   */
  public record Arsenal(
      Double arsFf,
      Double arsSi,
      Double arsFc,
      Double arsSl,
      Double arsCu,
      Double arsCh,
      Double arsOff,
      Double arsFfByCount,
      long pitcherPriorN) {}

  public Arsenal derive(
      long pitcherId,
      LocalDate gameDate,
      long gameId,
      int atBatIndex,
      int pitchNumber,
      int balls,
      int strikes,
      LocalDate today) {
    PitcherPitchTypePrior base =
        priors
            .findSnapshot(pitcherId, balls, strikes)
            .orElseThrow(
                () ->
                    new PriorUnavailable(
                        "no pitcher_pitchtype_prior_current row for pitcher "
                            + pitcherId
                            + "; the nightly refresh has never covered him. Refusing rather than"
                            + " composing a prior from the in-game delta alone, which would report"
                            + " this outing's pitches as if they were his whole career."));

    assertSnapshotIsFresh(base.asOfDate(), today, pitcherId);
    assertDeltaWindowIsValid(base.asOfDate(), gameDate, pitcherId);

    PitcherPitchTypeDelta d =
        priors.findInGameDelta(
            pitcherId, base.asOfDate(), gameDate, gameId, atBatIndex, pitchNumber, balls, strikes);

    long priorN = base.priorN() + d.priorN();
    long byCountN = base.priorNByCount() + d.priorNByCount();
    return new Arsenal(
        ratio(base.nFf() + d.nFf(), priorN),
        ratio(base.nSi() + d.nSi(), priorN),
        ratio(base.nFc() + d.nFc(), priorN),
        ratio(base.nSl() + d.nSl(), priorN),
        ratio(base.nCu() + d.nCu(), priorN),
        ratio(base.nCh() + d.nCh(), priorN),
        ratio(base.nOff() + d.nOff(), priorN),
        ratio(base.nFfByCount() + d.nFfByCount(), byCountN),
        priorN);
  }

  /**
   * The delta reads {@code pitches_live}, which carries a 14-day TTL. If the refresh job stops
   * while the overnight handoff keeps running, pitches between a stale {@code as_of_date} and now
   * migrate into {@code pitches} and then AGE OUT of {@code pitches_live} - counted by neither
   * half. That is the same gap V030's header section (c) exists to close, on a longer fuse, and it
   * is invisible: the prediction still returns, just against an undercounted career.
   */
  private void assertSnapshotIsFresh(LocalDate asOfDate, LocalDate today, long pitcherId) {
    long ageDays = java.time.temporal.ChronoUnit.DAYS.between(asOfDate, today);
    if (ageDays > maxSnapshotAgeDays) {
      throw new PriorUnavailable(
          "pitcher_pitchtype_prior_current is "
              + ageDays
              + " days stale (as_of_date="
              + asOfDate
              + ", limit="
              + maxSnapshotAgeDays
              + "). The in-game delta reads pitches_live, whose 14-day TTL means pitches after"
              + " as_of_date can age out of it while living only in pitches, so they would be"
              + " counted by neither half. Refusing pitcher "
              + pitcherId
              + " rather than serving a silently undercounted career prior; run the refresh job.");
    }
  }

  /**
   * The composition is only valid strictly AFTER {@code as_of_date}. On or before it the snapshot
   * already contains the current pitch and every later pitch of that game, so the delta returns
   * zeros and the composed frequency OVERCOUNTS - it includes pitches from the future of the row
   * being predicted. That is a temporal-leakage shape, not merely an inaccuracy.
   */
  private void assertDeltaWindowIsValid(LocalDate asOfDate, LocalDate gameDate, long pitcherId) {
    if (!gameDate.isAfter(asOfDate)) {
      throw new PriorUnavailable(
          "gameDate="
              + gameDate
              + " is not after as_of_date="
              + asOfDate
              + ", so the snapshot already contains this pitch and everything after it in the"
              + " game. Composing would count pitches from the future of the row being predicted."
              + " Refusing pitcher "
              + pitcherId
              + " rather than emitting a leaked prior.");
    }
  }

  /** NaN when the denominator is zero, matching the training SQL's NULL at a first career pitch. */
  private static Double ratio(long numerator, long denominator) {
    return denominator == 0L ? null : (double) numerator / (double) denominator;
  }

  /** Exposed for tests that need to assert the configured staleness bound. */
  public int maxSnapshotAgeDays() {
    return maxSnapshotAgeDays;
  }

  /** Convenience overload used by the serving path; {@code today} defaults to the ET game date. */
  public Arsenal derive(
      long pitcherId,
      LocalDate gameDate,
      long gameId,
      int atBatIndex,
      int pitchNumber,
      int balls,
      int strikes) {
    return derive(pitcherId, gameDate, gameId, atBatIndex, pitchNumber, balls, strikes, gameDate);
  }

  Optional<PitcherPitchTypePrior> snapshotFor(long pitcherId, int balls, int strikes) {
    return priors.findSnapshot(pitcherId, balls, strikes);
  }
}
