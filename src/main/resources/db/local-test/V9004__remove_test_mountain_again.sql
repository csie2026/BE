-- LOCAL ONLY. Removes the test mountain + course re-added by V9003, with a new migration;
-- V9000~V9003 stay untouched because they are already applied (Flyway checksum).
-- Child rows first, in FK order. Journals are kept, only unlinked from the activity.
UPDATE hiking_records SET hiking_activity_id = NULL
WHERE hiking_activity_id IN (SELECT id FROM hiking_activities WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산'));

DELETE FROM hiking_score_events
WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
   OR activity_id IN (SELECT id FROM hiking_activities WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산'));

DELETE FROM hiking_daily_certifications
WHERE activity_id IN (SELECT id FROM hiking_activities WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산'));

DELETE FROM hiking_activity_scores WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산');
DELETE FROM hiking_public_bonuses WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산');
DELETE FROM hiking_activities WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산');

-- Course sits on 테스트산 after V9001 (원미산 only if V9001 never ran).
DELETE FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638;
DELETE FROM mountains WHERE name = '테스트산';
