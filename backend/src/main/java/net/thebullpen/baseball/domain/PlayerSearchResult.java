package net.thebullpen.baseball.domain;

/**
 * Row returned from {@code GET /v1/players/search} (leaf 4b.1).
 *
 * <p>Mirrors the {@code players} ClickHouse table (V014): {@code id} for routing on selection,
 * {@code name} for the display label, {@code primaryPosition} for the trailing chip, {@code active}
 * so the UI can dim retired players, and {@code team} (V024 abbreviation, {@code ""} when
 * unaffiliated) for the Browse-by-team roster results.
 */
public record PlayerSearchResult(
    long id, String name, String primaryPosition, boolean active, String team) {}
