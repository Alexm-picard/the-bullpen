package net.thebullpen.baseball.data;

/**
 * The current Tier-3 form snapshot for one pitcher, read from {@code pitcher_form_current} (V007).
 * These are the six pitcher-side form features the live pre-pitch request can fill; the request's
 * remaining Tier-3 slots ({@code pitcherStrikeRateStd} and the four batter-side rates) are not
 * materialised in {@code pitcher_form_current} and stay null -&gt; NaN.
 *
 * <p>{@code daysSinceLastAppearance} is nullable to mirror the column; the others are non-null in a
 * persisted row.
 */
public record PitcherForm(
    double pitchesInGame,
    double pitchesLast28d,
    double strikeRate28d,
    double swstrikeRate28d,
    double inplayRate28d,
    Double daysSinceLastAppearance) {}
