# r/baseball

## Title

I built a self-hosted prediction site for MLB — pitch outcome, batted-ball per-park HR probability, and a live calibration dashboard

## Body

Hi r/baseball — over the past few months I've been building
**thebullpen.net**, a self-hosted analytics site that predicts pitch
outcomes and batted-ball home-run probability across every MLB park.

What's there today:

- **Park Explorer**: drag launch speed / angle / spray and see how the
  same batted ball plays at all 30 parks. Tint = predicted P(HR), dot
  = the model's estimated landing zone. Coors lights up; Petco doesn't.
- **Player lookup**: search by name or Statcast ID; per-player
  predictions history (will fill in as the season runs).
- **Ops dashboard** (public read): which model versions are live,
  drift sparklines, A/B routing splits, retrain queue, calibration
  summary.

Modeling choices that matter to this sub:

- **Rolling-origin cross-validation** (4 folds, 2015–2025) — never
  random splits. The temporal cut is by date, never by game or pitch,
  so a 2020 at-bat's first pitch can't leak into the training set with
  its second pitch in validation.
- **Isotonic calibration** on a held-out fold for the pitch-outcome
  multinomial.
- **Per-park batted-ball model** with a shared backbone + 30 per-park
  heads coming in Phase 2c.5; the v1 model is a 5-feature toy that
  serves as the spine. A logistic-regression baseline is always
  co-registered so you can see the lift.

Full methodology + decisions log on https://thebullpen.net/about and
https://github.com/Alexm-picard/the-bullpen.

Happy to take questions on the modeling or the data wrangling. The
play-by-play comes from Baseball Savant via pybaseball; nothing is
redistributed — outputs are derived analytics only.
