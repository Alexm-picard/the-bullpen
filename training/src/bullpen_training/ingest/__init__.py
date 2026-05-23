"""Data ingestion for The Bullpen.

Historical Statcast pulls (Phase 1.1) and the streaming live-game pipeline
(Phase 4d) both live here. The contract toward ClickHouse is that every
inserter writes a single normalised columnar payload via the native protocol.

Data source: MLB Statcast via pybaseball. Subject to MLB's terms of use;
data is for non-commercial research use only (see Risk Register I7).
"""
