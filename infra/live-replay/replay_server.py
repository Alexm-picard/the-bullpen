#!/usr/bin/env python3
"""Fixture-replay server for the live-data dry-run (issue #1; docs/runbooks/live-data-setup.md).

Serves ONLY the two MLB Stats API endpoints the poller calls, from a committed real-game feed, so
`LivePollingService` can be exercised with no live API and no game window. Point the worker at it:

    BULLPEN_INGEST_LIVE_BASE_URL=http://localhost:9099

Safety (reviewed): this is a pure fixture server - it never makes an outbound request, binds to
127.0.0.1 only, and 404s every path except the two it knows. It cannot accidentally hit the real
MLB API.

Deterministic replay: it progressively reveals at-bats from the full-game feed. Each `feed/live`
request returns a snapshot with status "In Progress", `allPlays` = the revealed at-bats, and
`currentPlay` = the next at-bat as a fresh (0-0, isComplete=false) plate appearance. So the poller
writes new pitches and predicts exactly one new upcoming pitch per reveal step. Re-polling the same
snapshot exercises the cursor + predict-next dedup (no double write/predict), which keeps
`keyed_preds == distinct_pitches` true. When the reveal exhausts, the feed reports "Final" and the
poller stops on the status transition.

Endpoints (the only two served):
    GET /api/v1/schedule?sportId=1&date=YYYY-MM-DD   -> the one replayed game, "In Progress"
    GET /api/v1.1/game/{gamePk}/feed/live            -> the next progressive snapshot

Usage:
    python3 infra/live-replay/replay_server.py [--feed PATH] [--port 9099]
        [--game-date YYYY-MM-DD] [--start-at 1] [--advance-per-request 1] [--self-test]
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

_REPO_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_FEED = _REPO_ROOT / "backend/src/test/resources/mlb/feed_live_824753.json"

# Synthetic replay rows carry a SENTINEL gamePk far above any real MLB gamePk (currently ~6 digits),
# so the rows they seed in pitches_live / prediction_log / live_game_status are permanently
# identifiable and excludable by range: game_id >= SENTINEL_GAME_PK_FLOOR is synthetic. Drift /
# calibration / eval / training MUST exclude this range (see docs/runbooks/live-data-setup.md).
SENTINEL_GAME_PK_FLOOR = 900_000_000
_DEFAULT_GAME_PK = (
    900_000_824  # the 824 echoes the source fixture (824753); clearly synthetic
)


def _validated_game_date(s: str) -> str:
    """Normalize + validate the date injected as gameData.datetime.officialDate.

    An empty or non-ISO value must never reach the wire (C-3 box finding, 2026-06-11): the Java
    side (MlbFeedParser.parseGameDate) treats a blank officialDate as missing and falls back to
    the fixture's REAL dateTime, so pitches_live / live_game_status rows land under the fixture's
    original date instead of today - invisible to /v1/games/today and the today-scoped
    verification queries. Blank normalizes to today; garbage raises ValueError (fail at startup,
    not on the wire)."""
    s = (s or "").strip()
    if not s:
        return _dt.date.today().isoformat()
    return _dt.date.fromisoformat(s).isoformat()


class Replay:
    """Holds the loaded full-game feed and builds progressive, deterministic snapshots."""

    def __init__(
        self,
        feed_path: Path,
        game_date: str,
        start_at: int,
        advance_per_request: int,
        game_pk: int,
        pregame_polls: int = 0,
    ):
        full = json.loads(feed_path.read_text())
        # A SENTINEL gamePk (not the fixture's real 824753) so synthetic replay rows in pitches_live
        # / prediction_log / live_game_status are unmistakable and permanently excludable by range
        # (game_id >= SENTINEL_GAME_PK_FLOOR), never colliding with real MLB data.
        self.game_pk = game_pk
        self.game_data = full["gameData"]
        self.all_plays = full["liveData"]["plays"]["allPlays"]
        self.game_date = _validated_game_date(game_date)
        self.advance_per_request = max(1, advance_per_request)
        self._reveal = max(1, start_at)
        self._requests = 0
        # Pre-game phase: the first `pregame_polls` SCHEDULE polls report the game
        # Scheduled (feed shows no plays), so a dry-run exercises the poller's
        # pre-game discovery + the SCHEDULED -> Live transition, not just live
        # polling. The game goes live once the schedule has been polled MORE than
        # `pregame_polls` times; the feed follows that flag.
        self._pregame_polls = max(0, pregame_polls)
        self._schedule_calls = 0
        self._lock = threading.Lock()

    @property
    def n_at_bats(self) -> int:
        return len(self.all_plays)

    def _entering_outs(self, at_bat: dict) -> int:
        for ev in at_bat.get("playEvents", []):
            if ev.get("isPitch"):
                return int(ev.get("count", {}).get("outs", 0) or 0)
        return 0

    def _current_play(self, idx: int) -> dict | None:
        """A fresh (0-0, incomplete) plate appearance for at-bat `idx` - the pitch about to be
        thrown. Returns None once the reveal has consumed every at-bat (game over)."""
        if idx >= self.n_at_bats:
            return None
        ab = self.all_plays[idx]
        about = dict(ab.get("about", {}))
        about["isComplete"] = False
        return {
            "about": about,
            "matchup": ab.get("matchup", {}),
            "count": {"balls": 0, "strikes": 0, "outs": self._entering_outs(ab)},
            "playEvents": [],
        }

    def _is_live(self) -> bool:
        """True once the schedule has been polled MORE than pregame_polls times.
        Caller holds the lock (or accepts a benign racy read for a status report)."""
        return self._schedule_calls > self._pregame_polls

    def snapshot(self) -> dict:
        """Return the current snapshot. While pre-game (schedule polled <= pregame_polls
        times) the game is Scheduled with no plays; once live, the reveal advances every
        Nth request to Final."""
        with self._lock:
            live = self._is_live()
            if live:
                idx = self._reveal
                self._requests += 1
                if self._requests % self.advance_per_request == 0:
                    self._reveal = min(self._reveal + 1, self.n_at_bats)
            else:
                idx = 0
        current = self._current_play(idx) if live else None
        in_progress = current is not None
        game_data = json.loads(json.dumps(self.game_data))  # deep copy
        if not live:
            detailed, abstract = "Scheduled", "Preview"
        elif in_progress:
            detailed, abstract = "In Progress", "Live"
        else:
            detailed, abstract = "Final", "Final"
        game_data.setdefault("status", {})["detailedState"] = detailed
        game_data.setdefault("status", {})["abstractGameState"] = abstract
        game_data.setdefault("datetime", {})["officialDate"] = self.game_date
        game_data.setdefault("game", {})[
            "pk"
        ] = self.game_pk  # sentinel, overriding the fixture pk
        # Pre-game: no plays revealed (the game has not started). Live: allPlays up to
        # the reveal, plus the about-to-be-thrown currentPlay.
        plays: dict = {"allPlays": self.all_plays[:idx] if live else []}
        if live and current is not None:
            plays["currentPlay"] = current
        return {
            "gamePk": self.game_pk,
            "gameData": game_data,
            "liveData": {"plays": plays},
        }

    def schedule(self, date: str) -> dict:
        """The replayed game. Reported Scheduled for the first pregame_polls polls
        (pre-game discovery), then In Progress so the poller starts polling its feed."""
        with self._lock:
            self._schedule_calls += 1
            live = self._is_live()
        detailed, abstract = (
            ("In Progress", "Live") if live else ("Scheduled", "Preview")
        )
        gd = self.game_data
        return {
            "dates": [
                {
                    "date": date,
                    "games": [
                        {
                            "gamePk": self.game_pk,
                            "status": {
                                "detailedState": detailed,
                                "abstractGameState": abstract,
                            },
                            "teams": {
                                "home": {"team": gd["teams"]["home"]},
                                "away": {"team": gd["teams"]["away"]},
                            },
                        }
                    ],
                }
            ]
        }


def _make_handler(replay: Replay):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, payload: dict, code: int = 200) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:  # noqa: N802 (http.server API)
            parsed = urlparse(self.path)
            path = parsed.path
            if path == "/api/v1/schedule":
                date = _query_param(parsed.query, "date") or replay.game_date
                self._json(replay.schedule(date))
            elif path.startswith("/api/v1.1/game/") and path.endswith("/feed/live"):
                self._json(replay.snapshot())
            else:
                # Pure fixture server: refuse anything else so it can't proxy to the real API.
                self._json(
                    {"error": "replay server serves only schedule + feed/live"},
                    code=404,
                )

        def log_message(self, fmt: str, *args) -> None:  # quiet by default
            return

    return Handler


def _query_param(query: str, key: str) -> str | None:
    for pair in query.split("&"):
        if "=" in pair:
            k, v = pair.split("=", 1)
            if k == key:
                return v
    return None


def _self_test(replay: Replay) -> int:
    """Prove determinism + the replay invariants without a running poller, across the
    full SCHEDULED -> Live -> Final arc. Returns 0 on PASS."""
    # Phase 1 - pre-game discovery: the first pregame_polls SCHEDULE polls report the
    # game Scheduled, and the feed shows no plays (it has not started). This is the
    # path the poller's pre-game discovery + the priming edge actually use.
    for i in range(replay._pregame_polls):
        sched = replay.schedule(replay.game_date)
        sstatus = sched["dates"][0]["games"][0]["status"]["detailedState"]
        assert (
            sstatus == "Scheduled"
        ), f"pre-game schedule poll {i} must be Scheduled, got {sstatus}"
        snap = replay.snapshot()
        assert (
            snap["gameData"]["status"]["detailedState"] == "Scheduled"
        ), "pre-game feed must report Scheduled"
        assert (
            snap["liveData"]["plays"]["allPlays"] == []
        ), "no plays before first pitch"
        assert (
            "currentPlay" not in snap["liveData"]["plays"]
        ), "no currentPlay before the game starts"

    # Phase 2 - transition: the next schedule poll flips the game to Live; this is the
    # transition the poller acts on to begin feed polling.
    sched = replay.schedule(replay.game_date)
    assert (
        sched["dates"][0]["games"][0]["status"]["detailedState"] == "In Progress"
    ), "schedule must transition to Live after the pre-game polls"

    # Phase 3 - live reveal to Final (the original invariants).
    seen_next: list[tuple[int, int]] = []
    last_n = -1
    for _ in range(replay.n_at_bats + 2):
        snap = replay.snapshot()
        # C-3 finding (2026-06-11): a blank/foreign officialDate keys the box's rows under the
        # fixture's ORIGINAL date (Java falls back to gameData.datetime.dateTime), hiding them
        # from /v1/games/today. The served date must be the validated replay date, always.
        official = snap["gameData"]["datetime"]["officialDate"]
        assert (
            official == replay.game_date
        ), "served officialDate must equal the replay game date"
        _dt.date.fromisoformat(official)  # and it must be ISO-parseable
        plays = snap["liveData"]["plays"]
        n = len(plays["allPlays"])
        assert n >= last_n, "allPlays must grow monotonically"
        last_n = n
        cp = plays.get("currentPlay")
        if cp is not None:
            assert cp["about"]["isComplete"] is False, "currentPlay must be incomplete"
            seen_next.append(
                (cp["about"]["atBatIndex"], 1)
            )  # next pitch is always pitch 1 here
            assert snap["gameData"]["status"]["detailedState"] == "In Progress"
    # The upcoming-pitch keys the poller would predict must be unique (no per-poll duplicate).
    assert len(seen_next) == len(
        set(seen_next)
    ), "upcoming-pitch keys must be unique across reveals"
    final = replay.snapshot()
    assert (
        final["gameData"]["status"]["detailedState"] == "Final"
    ), "reveal exhausts to Final"
    print(
        f"SELF-TEST PASS: {replay._pregame_polls} pre-game poll(s) (Scheduled) -> Live ->"
        f" {replay.n_at_bats} at-bats, {len(set(seen_next))} unique upcoming pitches,"
        f" monotonic allPlays, exhausts to Final, officialDate {replay.game_date}."
    )
    return 0


def main() -> int:
    p = argparse.ArgumentParser(
        description="Fixture-replay server for the live-data dry-run."
    )
    p.add_argument(
        "--feed", type=Path, default=_DEFAULT_FEED, help="full-game feed JSON to replay"
    )
    p.add_argument("--port", type=int, default=9099)
    p.add_argument(
        "--host", default="127.0.0.1", help="bind host (localhost only by default)"
    )
    p.add_argument(
        "--game-date",
        default=_dt.date.today().isoformat(),
        help="date to report the game on (default: today) so /v1/games/today finds it",
    )
    p.add_argument(
        "--start-at", type=int, default=1, help="at-bats revealed on the first poll"
    )
    p.add_argument(
        "--advance-per-request",
        type=int,
        default=1,
        help="reveal one more at-bat every Nth feed request (1 = progress each poll)",
    )
    p.add_argument(
        "--pregame-polls",
        type=int,
        default=3,
        help="report the game Scheduled (no plays) for the first N schedule polls, then"
        " transition to Live - exercises the poller's pre-game discovery (0 = live"
        " immediately, the pre-C2 behavior)",
    )
    p.add_argument(
        "--game-pk",
        type=int,
        default=_DEFAULT_GAME_PK,
        help=f"SENTINEL gamePk for the synthetic rows (default {_DEFAULT_GAME_PK}; must stay >="
        f" {SENTINEL_GAME_PK_FLOOR} so drift/eval can exclude the synthetic range)",
    )
    p.add_argument(
        "--self-test", action="store_true", help="run invariants check and exit"
    )
    args = p.parse_args()

    if args.game_pk < SENTINEL_GAME_PK_FLOOR:
        p.error(
            f"--game-pk must be >= {SENTINEL_GAME_PK_FLOOR} (the synthetic-row exclusion floor); a"
            " real-range gamePk would let replay rows masquerade as real data"
        )

    try:
        replay = Replay(
            args.feed,
            args.game_date,
            args.start_at,
            args.advance_per_request,
            args.game_pk,
            pregame_polls=args.pregame_polls,
        )
    except ValueError as e:
        p.error(f"--game-date {args.game_date!r} is not YYYY-MM-DD ({e})")
        raise AssertionError("unreachable")  # p.error exits; keeps type-checkers honest
    if args.self_test:
        return _self_test(replay)

    server = ThreadingHTTPServer((args.host, args.port), _make_handler(replay))
    print(
        f"replay: {replay.n_at_bats} at-bats from {args.feed.name} as gamePk {replay.game_pk} on"
        f" {args.game_date}; serving http://{args.host}:{args.port}"
        " (/api/v1/schedule + /api/v1.1/game/<pk>/feed/live only). Ctrl-C to stop."
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
