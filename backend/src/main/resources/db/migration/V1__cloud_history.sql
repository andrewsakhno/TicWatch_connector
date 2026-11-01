-- Every sample the phone stored from the watch (or derived from it). Codes are the phone's
-- SensorTypeCodes / SampleSource codes; the server stays agnostic of their meaning.
CREATE TABLE sensor_sample (
    id          BIGSERIAL PRIMARY KEY,
    type        SMALLINT         NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    recorded_at BIGINT           NOT NULL,
    ended_at    BIGINT,
    source      SMALLINT         NOT NULL,
    snapshot    BOOLEAN          NOT NULL DEFAULT FALSE,
    device_id   TEXT             NOT NULL,
    received_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Identity of a reading: a re-sent batch (lost ack, reinstall) is ignored. Also serves (type, time) range reads.
CREATE UNIQUE INDEX ux_sensor_sample_identity
    ON sensor_sample (type, recorded_at, source, ended_at, value) NULLS NOT DISTINCT;

CREATE TABLE route_point (
    recorded_at BIGINT           NOT NULL,
    source      SMALLINT         NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    altitude_m  DOUBLE PRECISION,
    accuracy_m  DOUBLE PRECISION,
    device_id   TEXT             NOT NULL,
    PRIMARY KEY (recorded_at, source)
);

-- Watch rhythm strips (beats, pulse wave, analysis) as the phone serialised them.
CREATE TABLE ecg_recording (
    started_at  BIGINT      PRIMARY KEY,
    payload     JSONB       NOT NULL,
    device_id   TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
