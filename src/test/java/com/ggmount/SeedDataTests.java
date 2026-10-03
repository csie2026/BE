package com.ggmount;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Flyway V2(mountains)·V3(courses) 시드 데이터의 무결성. 데이터를 다시 생성하면 숫자 기대값도 함께 갱신합니다. */
@SpringBootTest
@Transactional
class SeedDataTests {
    @Autowired
    JdbcTemplate jdbc;

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void mountains() {
        assertEquals(140, count("select count(*) from mountains"));
        // 도라산(id 112) 제외: id는 재사용하지 않음
        assertEquals(141, count("select max(id) from mountains"));
        assertEquals(31, count("select count(distinct sigungu_name) from mountains"));
        assertEquals(31, count("select count(distinct sigungu_code) from mountains"));
        assertEquals(
            0,
            count("select count(*) from (select sigungu_name from mountains group by sigungu_name having count(distinct region) > 1 or count(distinct sigungu_code) > 1) x")
        );
        assertEquals(
            0,
            count("select count(*) from mountains where description is null or description = ''")
        );
        assertEquals(0, count("select count(*) from mountains where height < 30 or height > 1500"));
        assertEquals(
            0,
            count("select count(*) from mountains where lat not between 36.8 and 38.4 or lng not between 126.3 and 127.9")
        );
        assertEquals(
            0,
            count("select count(*) from mountains where grid_nx not between 1 and 149 or grid_ny not between 1 and 253")
        );
        // 새 산은 시드 id 다음 번호를 받아야 합니다
        jdbc.update("insert into mountains (name, region, sigungu_code, sigungu_name, address, height, lat, lng, grid_nx, grid_ny) "
            + "values ('테스트산', 'SOUTH', '41460', '용인시', '주소', 100, 37.2, 127.2, 62, 120)");
        assertEquals(142, count("select id from mountains where name = '테스트산'"));
    }

    @Test
    void courses() {
        assertEquals(420, count("select count(*) from courses"));
        assertEquals(134, count("select count(distinct mountain_id) from courses"));
        assertEquals(
            0,
            count("select count(*) from courses c left join mountains m on m.id = c.mountain_id where m.id is null")
        );
        assertEquals(
            0,
            count("select count(*) from (select mountain_id from courses group by mountain_id having count(*) > 5) x")
        );
        assertEquals(
            0,
            count("select count(*) from courses c join mountains m on m.id = c.mountain_id "
                + "where abs(m.height - c.start_elevation - c.climb) > 1")
        );
        assertEquals(
            0,
            count("select count(*) from courses where climb > 0.35 * length_km * 1000 or length_km <= 0")
        );
        assertEquals(
            0,
            count("select count(*) from courses where up_min <= 0 or down_min <= 0 or down_min > up_min")
        );

        List<Map<String, Object>> rows = jdbc.queryForList(
            "select c.name, c.start_lat, c.start_lng, c.path, m.lat, m.lng from courses c join mountains m on m.id = c.mountain_id");
        for (Map<String, Object> r : rows) {
            List<Object> path = JsonParserFactory.getJsonParser().parseList((String) r.get("PATH"));
            assertTrue(path.size() >= 2, r.get("NAME").toString());
            List<?> first =(List<?>) path.getFirst(), last =(List<?>) path.getLast();
            assertEquals(
                (Double) r.get("START_LNG"),
                ((Number) first.get(0)).doubleValue(),
                1e-5,
                "start " + r.get("NAME")
            );
            assertEquals(
                (Double) r.get("START_LAT"),
                ((Number) first.get(1)).doubleValue(),
                1e-5,
                "start " + r.get("NAME")
            );
            double dLat =((Number) last.get(1)).doubleValue() - (Double) r.get("LAT");
            double dLng =(((Number) last.get(0))
                .doubleValue() - (Double) r.get("LNG")) * Math.cos(Math.toRadians(37.5));
            assertTrue(
                Math.hypot(dLat,
                    dLng) * 111_000 < 300,
                "path should end at the summit: " + r.get("NAME")
            );
        }
    }
}
