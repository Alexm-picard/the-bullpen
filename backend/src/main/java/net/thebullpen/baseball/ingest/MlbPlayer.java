package net.thebullpen.baseball.ingest;

/**
 * One player row from the MLB Stats API roster document ({@code /api/v1/sports/1/players}), shaped
 * for the {@code players} ClickHouse dimension (V014). Width clamping to the table's FixedString
 * columns happens in {@link MlbFeedParser#parsePlayers}; this record carries already-clamped
 * values.
 *
 * <p>{@code throwsHand} because {@code throws} is a Java keyword; it maps to the table's {@code
 * throws} column.
 *
 * <p>{@code team} is the MLB abbreviation of the player's current club (BOS, NYY, AZ, ATH, ...),
 * matching the frontend teamColors keys; {@code ""} when the roster document carries no currentTeam
 * (free agent / inactive). Added V024 for Browse-by-team.
 */
public record MlbPlayer(
    long id,
    String name,
    String primaryPosition,
    String bats,
    String throwsHand,
    boolean active,
    String team) {}
