package net.thebullpen.baseball.ingest;

import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.LivePitch;

/**
 * A parsed MLB GUMBO live feed ({@code /api/v1.1/game/{pk}/feed/live}) for one game: the current
 * lifecycle status plus every pitch seen so far, in order. {@code homeAbbrev} is the v1 {@code
 * park_id} (decision in V003: {@code park_id == home_team}).
 */
public record LiveGameFeed(
    long gamePk,
    GameStatus status,
    LocalDate gameDate,
    int homeTeamId,
    int awayTeamId,
    String homeAbbrev,
    String awayAbbrev,
    List<LivePitch> pitches,
    // The pitch about to be thrown (decision [143] predict-next), from currentPlay; null when the
    // game isn't awaiting a pitch (between at-bats, or final).
    LiveNextPitch nextPitch) {}
