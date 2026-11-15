-- LOCAL ONLY test course (application-local.yml flyway.locations). Never in prod/tests.
-- Attached to existing nearest mountain 원미산 (courses.mountain_id is NOT NULL).
INSERT INTO courses (mountain_id, source, name, start_name, start_lat, start_lng, start_elevation, climb, length_km, up_min, down_min, difficulty, risk, path)
SELECT id, 'OSM', '테스트 코스', '테스트 시작점', 37.4849638, 126.8081153, 10, 10, 0.16, 3, 3, 'EASY', NULL,
 '[[126.8081153,37.4849638],[126.8074,37.4846],[126.8064429,37.4843631]]'
FROM mountains WHERE name = '원미산';
