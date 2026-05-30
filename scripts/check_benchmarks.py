#!/usr/bin/env python3
"""Compare a JMH results.json against a committed baseline and fail on regression.

Plan S1g. The backend `benchmark` workflow runs `./gradlew jmh` nightly, then runs
this script to flag any inference-hot-path benchmark that got more than
`--threshold` (default 25%) slower than the committed baseline at
`backend/benchmarks/baseline.json`.

JMH AverageTime mode → lower score = faster, so a regression is
`current.score > baseline.score * (1 + threshold)`. New benchmarks not present in
the baseline are reported but don't fail (add them to the baseline deliberately).

Usage:
    python scripts/check_benchmarks.py \
        --current backend/build/results/jmh/results.json \
        --baseline backend/benchmarks/baseline.json \
        --threshold 0.25
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _load(path: Path) -> dict[str, dict[str, float]]:
    """Map benchmark FQN -> {score, error, unit} from a JMH JSON file."""
    raw = json.loads(path.read_text())
    out: dict[str, dict[str, float]] = {}
    for entry in raw:
        metric = entry["primaryMetric"]
        out[entry["benchmark"]] = {
            "score": float(metric["score"]),
            "error": float(metric.get("scoreError", 0.0) or 0.0),
            "unit": metric["scoreUnit"],
        }
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="JMH regression gate.")
    ap.add_argument("--current", type=Path, required=True)
    ap.add_argument("--baseline", type=Path, required=True)
    ap.add_argument("--threshold", type=float, default=0.25)
    args = ap.parse_args()

    if not args.current.exists():
        print(f"ERROR: current results not found: {args.current}", file=sys.stderr)
        return 2
    current = _load(args.current)

    if not args.baseline.exists():
        print(
            f"NOTE: no baseline at {args.baseline} — printing current scores so you "
            "can seed one. Not failing.",
        )
        for name, m in sorted(current.items()):
            short = name.rsplit(".", 1)[-1]
            print(f"  {short:<28} {m['score']:>10.3f} ± {m['error']:.3f} {m['unit']}")
        return 0
    baseline = _load(args.baseline)

    regressions: list[str] = []
    print(f"benchmark gate: current vs baseline (fail at +{args.threshold:.0%})")
    print(f"{'benchmark':<30}{'baseline':>12}{'current':>12}{'delta':>10}")
    for name in sorted(current):
        short = name.rsplit(".", 1)[-1]
        cur = current[name]
        base = baseline.get(name)
        if base is None:
            print(f"{short:<30}{'(new)':>12}{cur['score']:>12.3f}{'—':>10}")
            continue
        delta = (cur["score"] - base["score"]) / base["score"] if base["score"] else 0.0
        flag = "  <-- REGRESSION" if delta > args.threshold else ""
        print(
            f"{short:<30}{base['score']:>12.3f}{cur['score']:>12.3f}{delta:>9.1%}{flag}"
        )
        if delta > args.threshold:
            regressions.append(
                f"{short}: {base['score']:.3f} -> {cur['score']:.3f} {cur['unit']} "
                f"(+{delta:.1%})"
            )

    if regressions:
        print(
            f"\nFAIL: {len(regressions)} benchmark(s) regressed >{args.threshold:.0%}:"
        )
        for r in regressions:
            print(f"  - {r}")
        return 1
    print("\nOK: no benchmark regressed beyond the threshold.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
