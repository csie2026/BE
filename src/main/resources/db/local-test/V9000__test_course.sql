-- LOCAL ONLY test mountain + course (application-local.yml flyway.locations). Never in prod/tests.
-- Own mountain so summit coords (= course end point) can be reached; real 원미산 stays untouched.
-- Explicit id: V2 seeds explicit ids, so the identity sequence may not be advanced.
INSERT INTO mountains (id, name, region, sigungu_code, sigungu_name, address, height, lat, lng, grid_nx, grid_ny, description)
SELECT (SELECT MAX(id) + 1 FROM mountains), '테스트산', region, sigungu_code, sigungu_name, address, 10, 37.4843631, 126.8064429, grid_nx, grid_ny, '로컬 테스트용 산'
FROM mountains WHERE name = '원미산';

INSERT INTO courses (mountain_id, source, name, start_name, start_lat, start_lng, start_elevation, climb, length_km, up_min, down_min, difficulty, risk, path)
SELECT id, 'OSM', '테스트 코스', '테스트 시작점', 37.4849638, 126.8081153, 10, 10, 0.16, 3, 3, 'EASY', NULL,
 '[[126.8081153,37.4849638],[126.8074,37.4846],[126.8064429,37.4843631]]'
FROM mountains WHERE name = '테스트산';
