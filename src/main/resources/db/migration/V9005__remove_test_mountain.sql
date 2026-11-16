-- Removes the test mountain + test course (V9000/V9001) from regular DBs, with a new migration;
-- earlier versions stay untouched (already applied, Flyway checksum). No-op when 테스트산 does not exist.
-- Test activities are matched by mountain OR by the test course: while V9000 attached the course to 원미산,
-- activities recorded on it have mountain_id = 원미산 and only course_id = test course (courses FK).
-- Child rows first, in FK order. Journals are kept, only unlinked from the activity.
UPDATE hiking_records SET hiking_activity_id = NULL
WHERE hiking_activity_id IN (
    SELECT id FROM hiking_activities
    WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
       OR course_id IN (SELECT id FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638));

DELETE FROM hiking_score_events
WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
   OR activity_id IN (
    SELECT id FROM hiking_activities
    WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
       OR course_id IN (SELECT id FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638));

DELETE FROM hiking_daily_certifications
WHERE activity_id IN (
    SELECT id FROM hiking_activities
    WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
       OR course_id IN (SELECT id FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638));

DELETE FROM hiking_activity_scores
WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
   OR activity_id IN (
    SELECT id FROM hiking_activities
    WHERE course_id IN (SELECT id FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638));

DELETE FROM hiking_public_bonuses WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산');

DELETE FROM hiking_activities
WHERE mountain_id IN (SELECT id FROM mountains WHERE name = '테스트산')
   OR course_id IN (SELECT id FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638);

-- Course sits on 테스트산 after V9001 (원미산 only if V9001 never ran).
DELETE FROM courses WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638;
DELETE FROM mountains WHERE name = '테스트산';
