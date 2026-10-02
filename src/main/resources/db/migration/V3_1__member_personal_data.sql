-- Member personal data only. Existing V1/V2/V3 and the planned journal V4 are unchanged.
CREATE TABLE member_monthly_goals (
    member_id BIGINT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    goal_month DATE NOT NULL,
    target_distance_km NUMERIC(8,3) NOT NULL CHECK (target_distance_km > 0),
    target_hiking_count INTEGER NOT NULL CHECK (target_hiking_count BETWEEN 1 AND 10000),
    PRIMARY KEY (member_id, goal_month),
    CHECK (EXTRACT(DAY FROM goal_month) = 1)
);

CREATE TABLE member_personal_images (
    member_id BIGINT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('profile', 'background')),
    content BYTEA NOT NULL CHECK (OCTET_LENGTH(content) BETWEEN 1 AND 5242880),
    media_type VARCHAR(32) NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    revision VARCHAR(36) NOT NULL,
    PRIMARY KEY (member_id, kind)
);
