-- What surrounded a deviation episode when the phone found it, and how strongly the wearer felt it; all null on
-- episodes found before. minor: stored but never asked about (short and shallow). intensity: 1 barely, 2 noticeably,
-- 3 strongly. threshold_bpm: the corridor edge crossed; baseline_bpm: the personal norm behind it (null = fixed fallback);
-- excess_bpm_minutes: bpm x minutes outside the corridor; worn_since: epoch ms the readings resumed after the watch was
-- off; steps_before: steps in the 30 min before; rmssd_before: mean RMSSD of the hour before; sleep_score: last night's;
-- fatigue_percent: the fatigue index of the hour before; last_medication_at: epoch ms of the last intake in 48 h.
-- New reason codes: 4 stood up or moved, 23 ate, 61 just put the watch on.
ALTER TABLE deviation_episode
    ADD COLUMN minor              BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN intensity          SMALLINT,
    ADD COLUMN threshold_bpm      DOUBLE PRECISION,
    ADD COLUMN baseline_bpm       DOUBLE PRECISION,
    ADD COLUMN excess_bpm_minutes DOUBLE PRECISION,
    ADD COLUMN worn_since         BIGINT,
    ADD COLUMN steps_before       DOUBLE PRECISION,
    ADD COLUMN rmssd_before       DOUBLE PRECISION,
    ADD COLUMN sleep_score        DOUBLE PRECISION,
    ADD COLUMN fatigue_percent    DOUBLE PRECISION,
    ADD COLUMN last_medication_at BIGINT;
