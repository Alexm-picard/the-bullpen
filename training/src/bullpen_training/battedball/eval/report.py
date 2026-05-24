"""JSON + HTML rendering for the 2c.9 MLP vs LGBM comparison report.

Two artefacts per run:
  - ``batted_ball_comparison_v1.json`` — machine-readable, the
    canonical record for downstream tooling + a future Java reader.
  - ``batted_ball_comparison_v1.html`` — single-page human-readable
    side-by-side, ships in the eval bundle so the user can review
    the bake-off in a browser.

HTML uses inline CSS only — no external assets — so the artifact is
self-contained and works offline.
"""

from __future__ import annotations

import json
from pathlib import Path

from bullpen_training.battedball.eval.comparison import (
    ComparisonReport,
    report_to_dict,
)


def save_report(report: ComparisonReport, json_path: Path, html_path: Path | None = None) -> None:
    """Persist the report as JSON + (optionally) HTML."""
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report_to_dict(report), indent=2))
    if html_path is not None:
        html_path.parent.mkdir(parents=True, exist_ok=True)
        html_path.write_text(render_html(report))


def render_html(report: ComparisonReport) -> str:
    """Render the report to a single self-contained HTML page."""
    agg_mlp = report.aggregate["mlp"]
    agg_lgbm = report.aggregate["lgbm"]
    winner = report.prefer_for_production
    winner_style = "color:#0b6;" if winner == "mlp" else "color:#06b;"

    # Per-park side-by-side table: one row per park, MLP cols + LGBM cols.
    rows_html: list[str] = []
    mlp_by_park = {p.park_id: p for p in report.per_park if p.model == "mlp"}
    lgbm_by_park = {p.park_id: p for p in report.per_park if p.model == "lgbm"}
    for pid in report.park_order:
        m = mlp_by_park.get(pid)
        lg = lgbm_by_park.get(pid)
        if m is None or lg is None:
            continue
        better_brier = "mlp" if m.brier < lg.brier else "lgbm"
        better_ece = "mlp" if m.ece < lg.ece else "lgbm"
        rows_html.append(
            "<tr>"
            f"<td>{pid}</td>"
            f"<td>{m.n_samples}</td>"
            f"<td class='{_cell_class(better_brier == 'mlp')}'>{m.brier:.4f}</td>"
            f"<td class='{_cell_class(better_brier == 'lgbm')}'>{lg.brier:.4f}</td>"
            f"<td class='{_cell_class(better_ece == 'mlp')}'>{m.ece:.4f}</td>"
            f"<td class='{_cell_class(better_ece == 'lgbm')}'>{lg.ece:.4f}</td>"
            f"<td>{m.accuracy:.3f}</td>"
            f"<td>{lg.accuracy:.3f}</td>"
            "</tr>"
        )

    rationale_html = "<ul>" + "".join(f"<li>{r}</li>" for r in report.rationale) + "</ul>"
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Batted-ball MLP vs LGBM comparison (v1)</title>
  <style>
    body {{ font-family: -apple-system, system-ui, sans-serif; max-width: 1100px;
            margin: 2rem auto; padding: 0 1rem; color: #222; }}
    h1, h2 {{ color: #111; }}
    .winner-banner {{ padding: 0.8rem; border-radius: 6px;
                      background: #f6f8fa; border-left: 4px solid currentColor;
                      {winner_style} font-weight: 600; margin: 1rem 0; }}
    table {{ border-collapse: collapse; width: 100%; margin-top: 1rem; }}
    th, td {{ padding: 0.4rem 0.6rem; border-bottom: 1px solid #eee;
              text-align: right; font-variant-numeric: tabular-nums; }}
    th {{ background: #fafafa; text-align: center; }}
    td:first-child, th:first-child {{ text-align: left; font-weight: 600; }}
    .better {{ background: #e7f6ec; }}
    .agg {{ font-size: 1.1rem; margin: 1rem 0; }}
    .agg span {{ display: inline-block; margin-right: 1.4rem; }}
    .rationale {{ background: #fafbfc; padding: 0.8rem 1.2rem;
                  border-radius: 6px; font-size: 0.95rem; }}
  </style>
</head>
<body>
  <h1>Batted-ball MLP vs LGBM &mdash; v1 comparison</h1>
  <div class="winner-banner">
    Production champion (auto-decided): <strong>{winner.upper()}</strong>
  </div>
  <h2>Aggregate (mean across {len(report.park_order)} parks)</h2>
  <div class="agg">
    <span><strong>MLP:</strong> Brier {agg_mlp.mean_brier:.4f} &middot;
      ECE {agg_mlp.mean_ece:.4f} &middot; Acc {agg_mlp.mean_accuracy:.3f}</span>
    <span><strong>LGBM:</strong> Brier {agg_lgbm.mean_brier:.4f} &middot;
      ECE {agg_lgbm.mean_ece:.4f} &middot; Acc {agg_lgbm.mean_accuracy:.3f}</span>
  </div>
  <h2>Decision rationale</h2>
  <div class="rationale">{rationale_html}</div>
  <h2>Per-park metrics (lower Brier / ECE is better; greener cell wins)</h2>
  <table>
    <thead>
      <tr>
        <th rowspan="2">Park</th>
        <th rowspan="2">N</th>
        <th colspan="2">Brier</th>
        <th colspan="2">ECE</th>
        <th colspan="2">Accuracy</th>
      </tr>
      <tr>
        <th>MLP</th><th>LGBM</th>
        <th>MLP</th><th>LGBM</th>
        <th>MLP</th><th>LGBM</th>
      </tr>
    </thead>
    <tbody>
      {"".join(rows_html)}
    </tbody>
  </table>
</body>
</html>
"""


def _cell_class(is_better: bool) -> str:
    return "better" if is_better else ""


__all__ = ("render_html", "save_report")
