-- UNIQUE creation checks every existing row and fails if duplicates exist.
-- Flyway applies this transactionally on PostgreSQL; never delete/merge history.
ALTER TABLE hiking_activities ADD CONSTRAINT hiking_activity_session_unique
    UNIQUE (member_id, mountain_id, started_at, ended_at);

ALTER TABLE hiking_activities ADD COLUMN gps_validation_version SMALLINT;
ALTER TABLE hiking_activities ADD COLUMN request_fingerprint_version SMALLINT;
ALTER TABLE hiking_activities ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE hiking_activities ADD CONSTRAINT hiking_activity_integrity_metadata_check CHECK (
    (gps_validation_version IS NULL AND request_fingerprint_version IS NULL AND request_fingerprint IS NULL)
    OR
    (gps_validation_version IS NOT NULL AND gps_validation_version = 1
     AND request_fingerprint_version IS NOT NULL AND request_fingerprint_version = 1
     AND request_fingerprint IS NOT NULL AND CHAR_LENGTH(request_fingerprint) = 64)
);

-- Existing rows stay NULL. Old writers must not silently bypass GPS validation.
ALTER TABLE hiking_activities ALTER COLUMN gps_validation_version SET DEFAULT 1;
ALTER TABLE hiking_activities ALTER COLUMN request_fingerprint_version SET DEFAULT 1;
