-- Every device that ever said hello; role and account are set by hand in the admin page.
CREATE TABLE device (
    device_id     TEXT PRIMARY KEY,
    name          TEXT   NOT NULL,
    role          TEXT   NOT NULL DEFAULT 'UNASSIGNED',
    account_id    TEXT   NOT NULL DEFAULT '',
    first_seen_at BIGINT NOT NULL,
    last_seen_at  BIGINT NOT NULL
);

-- The accounts parents publish for: what the admin page offers for a child device.
CREATE TABLE account (
    account_id TEXT PRIMARY KEY,
    name       TEXT NOT NULL
);

-- One parent phone's current state of one topic for one account (full state, as in the Nearby protocol).
CREATE TABLE topic_state (
    source_id       TEXT   NOT NULL,
    account_id      TEXT   NOT NULL,
    topic_id        TEXT   NOT NULL,
    owner_device_id TEXT   NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    manifest        TEXT   NOT NULL,
    updated_at      BIGINT NOT NULL,
    PRIMARY KEY (source_id, account_id, topic_id)
);

-- Bulk content a manifest refers to by key; dropped as soon as the manifest stops listing it.
CREATE TABLE topic_blob (
    source_id  TEXT  NOT NULL,
    account_id TEXT  NOT NULL,
    topic_id   TEXT  NOT NULL,
    blob_key   TEXT  NOT NULL,
    data       BYTEA NOT NULL,
    PRIMARY KEY (source_id, account_id, topic_id, blob_key),
    FOREIGN KEY (source_id, account_id, topic_id)
        REFERENCES topic_state (source_id, account_id, topic_id) ON DELETE CASCADE
);
