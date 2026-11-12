-- Keep existing member/month rows; each target may now be disabled independently.
ALTER TABLE member_monthly_goals ALTER COLUMN target_distance_km DROP NOT NULL;
ALTER TABLE member_monthly_goals ALTER COLUMN target_hiking_count DROP NOT NULL;
-- No-target means no row (the API deletes only that member/month's settings).
ALTER TABLE member_monthly_goals ADD CONSTRAINT monthly_goal_has_target
 CHECK (target_distance_km IS NOT NULL OR target_hiking_count IS NOT NULL);
