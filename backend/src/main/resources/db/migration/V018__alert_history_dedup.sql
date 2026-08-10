-- D-36 Part B: defense-in-depth dedup for alert_history. The rolling-24h firedWithin() stays the
-- PRIMARY business dedup; this blocks a same-day double-INSERT if two worker instances both pass it.
-- date(fired_at) is the UTC calendar day. The drift alerts fire at 03:00 America/New_York
-- (~07:00-08:00 UTC), mid-UTC-day, so UTC-day == ET-day for them - the index is ET-safe for the
-- current alert schedule. (If an alert ever fires near UTC midnight, revisit with an ET-shifted key.)
-- Collapse any existing duplicates first, keeping the earliest row per (alert_key, UTC day):
DELETE FROM alert_history
WHERE id NOT IN (SELECT MIN(id) FROM alert_history GROUP BY alert_key, date(fired_at));
CREATE UNIQUE INDEX ux_ah_key_day ON alert_history(alert_key, date(fired_at));
