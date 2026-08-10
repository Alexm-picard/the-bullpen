package net.thebullpen.baseball.data;

/**
 * The in-game half of the Tier-ARS reconstruction: one pitcher's pitch-TYPE counts for the pitches
 * they have already thrown STRICTLY BEFORE the pitch being predicted, read from {@code
 * pitches_live} (V030 header section (b)).
 *
 * <p>Field names deliberately mirror {@link PitcherPitchTypePrior} so the composition is a
 * mechanical field-by-field addition:
 *
 * <pre>{@code
 * ars_c = (base.nC() + delta.nC()) / (base.priorN() + delta.priorN())
 * }</pre>
 *
 * <p>All-zero is the normal, expected value: it means the pitcher has thrown nothing yet since the
 * snapshot's {@code as_of_date}. It is never "missing" - the query is an unGROUPed aggregate, so it
 * always returns exactly one row - and it must NOT be treated as an error or a cold start. The only
 * cold start is a composed {@code priorN} of 0.
 *
 * <p>Counts LABELED pitches only, applying the same {@code pitch_type NOT IN ('', 'PO', 'IN')}
 * filter as {@code compute_pitch_type_arsenal.sql}; an as-yet-untyped live pitch is therefore
 * absent from both the numerator and the denominator, exactly as in training.
 */
public record PitcherPitchTypeDelta(
    long priorN,
    long nFf,
    long nSi,
    long nFc,
    long nSl,
    long nCu,
    long nCh,
    long nOff,
    long priorNByCount,
    long nFfByCount) {}
