-- LOCAL ONLY (application-local.yml flyway.locations). Never in prod/tests.
-- V9000 attached the test course to 원미산, whose summit is ~1.3km from the course end,
-- so a completed hike was never recognised. Move it to its own test mountain whose summit
-- is the course end point. V9000 is left untouched: it is already applied on existing DBs.
-- Explicit id: V2 seeds explicit ids, so the identity sequence may not be advanced.
INSERT INTO mountains (id, name, region, sigungu_code, sigungu_name, address, height, lat, lng, grid_nx, grid_ny, description)
SELECT (SELECT MAX(id) + 1 FROM mountains), '테스트산', region, sigungu_code, sigungu_name, address, 10, 37.4843631, 126.8064429, grid_nx, grid_ny, '로컬 테스트용 산'
FROM mountains WHERE name = '원미산'
  AND NOT EXISTS (SELECT 1 FROM mountains WHERE name = '테스트산');

UPDATE courses
SET mountain_id = (SELECT id FROM mountains WHERE name = '테스트산')
WHERE name = '테스트 코스' AND source = 'OSM' AND start_lat = 37.4849638
  AND mountain_id = (SELECT id FROM mountains WHERE name = '원미산');
