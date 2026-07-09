-- V026 - post-pitch Tier-4 movement + release on the live-pitch table (F2.1a, decision [177]).
--
-- The live pitch_outcome_post retrospective panel needs the full 10-field Tier-4 vector per
-- completed pitch. pitches_live already carried pitch_type, release_speed_mph, plate_x_in,
-- plate_z_in; this adds the remaining six. The four spatial fields are DERIVED from the GUMBO
-- 9-parameter fit (see GumboKinematics) so they match the pitches-table columns the model trained
-- on (raw-Statcast units - the "_in" suffix is a misnomer, no conversion); spin is a validated
-- pass-through. Nullable(Float32) like the existing Tier-4 columns: the live feed's fit is missing
-- on the ~0.2-1% of pitches with a tracking blip, and the post leg skips those (a completeness gate,
-- never NaN-fed).
--
-- Additive ALTER; pitches_live is a ReplacingMergeTree on (game_id, at_bat_index, pitch_number), so
-- re-polled/corrected pitches overwrite on FINAL reads - no backfill DML needed. The bullpen grant
-- (decision [171]) includes ALTER ADD COLUMN for exactly this boot-time migration path.
ALTER TABLE pitches_live ADD COLUMN IF NOT EXISTS pfx_x_in Nullable(Float32) AFTER plate_z_in;
ALTER TABLE pitches_live ADD COLUMN IF NOT EXISTS pfx_z_in Nullable(Float32) AFTER pfx_x_in;
ALTER TABLE pitches_live ADD COLUMN IF NOT EXISTS spin_rate_rpm Nullable(Float32) AFTER pfx_z_in;
ALTER TABLE pitches_live ADD COLUMN IF NOT EXISTS spin_axis_deg Nullable(Float32) AFTER spin_rate_rpm;
ALTER TABLE pitches_live ADD COLUMN IF NOT EXISTS release_pos_x_in Nullable(Float32) AFTER spin_axis_deg;
ALTER TABLE pitches_live ADD COLUMN IF NOT EXISTS release_pos_z_in Nullable(Float32) AFTER release_pos_x_in;
