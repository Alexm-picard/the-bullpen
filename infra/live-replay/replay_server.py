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


class Replay:
    """Holds the loaded full-game feed and builds progressive, deterministic snapshots."""

    def __init__(
        self,
        feed_path: Path,
        game_date: str,
        start_at: int,
        advance_per_request: int,
        game_pk: int,
    ):
        full = json.loads(feed_path.read_text())
        # A SENTINEL gamePk (not the fixture's real 824753) so synthetic replay rows in pitches_live
        # / prediction_log / live_game_status are unmistakable and permanently excludable by range
        # (game_id >= SENTINEL_GAME_PK_FLOOR), never colliding with real MLB data.
        self.game_pk = game_pk
        self.game_data = full["gameData"]
        self.all_plays = full["liveData"]["plays"]["allPlays"]
        self.game_date = game_date
        self.advance_per_request = max(1, advance_per_request)
        self._reveal = max(1, start_at)
        self._requests = 0
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

    def snapshot(self) -> dict:
        """Return the current snapshot and advance the reveal index (every Nth request)."""
        with self._lock:
            idx = self._reveal
            self._requests += 1
            if self._requests % self.advance_per_request == 0:
                self._reveal = min(self._reveal + 1, self.n_at_bats)
        current = self._current_play(idx)
        in_progress = current is not None
        game_data = json.loads(json.dumps(self.game_data))  # deep copy
        game_data.setdefault("status", {})["detailedState"] = (
            "In Progress" if in_progress else "Final"
        )
        game_data.setdefault("status", {})["abstractGameState"] = (
            "Live" if in_progress else "Final"
        )
        game_data.setdefault("datetime", {})["officialDate"] = self.game_date
        game_data.setdefault("game", {})[
            "pk"
        ] = self.game_pk  # sentinel, overriding the fixture pk
        plays: dict = {"allPlays": self.all_plays[:idx]}
        if current is not None:
            plays["currentPlay"] = current
        return {
            "gamePk": self.game_pk,
            "gameData": game_data,
            "liveData": {"plays": plays},
        }

    def schedule(self, date: str) -> dict:
        """The replayed game, always reported as In Progress so the poller starts polling it."""
        gd = self.game_data
        return {
            "dates": [
                {
                    "date": date,
                    "games": [
                        {
                            "gamePk": self.game_pk,
                            "status": {
                                "detailedState": "In Progress",
                                "abstractGameState": "Live",
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
    """Prove determinism + the replay invariants without a running poller. Returns 0 on PASS."""
    seen_next: list[tuple[int, int]] = []
    last_n = -1
    for _ in range(replay.n_at_bats + 2):
        snap = replay.snapshot()
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
        f"SELF-TEST PASS: {replay.n_at_bats} at-bats, {len(set(seen_next))} unique upcoming pitches,"
        " monotonic allPlays, exhausts to Final."
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

    replay = Replay(
        args.feed, args.game_date, args.start_at, args.advance_per_request, args.game_pk
    )
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
