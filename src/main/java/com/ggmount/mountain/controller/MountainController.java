package com.ggmount.mountain.controller;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.json.JsonParserFactory;

import java.util.List;

// Flyway로 적재된 산·코스를 DB ID로 연결해 제공한다. 경로는 저장된 GeoJSON 좌표 순서를 유지한다.
@RestController
public class MountainController {
    private final JdbcTemplate jdbc;

    public MountainController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Mountain(
        long id,
        String name,
        String city,
        double height,
        double lat,
        double lng,
        int courseCount
    ) {
    }
    public record Course(
        long id,
        long mountainId,
        String name,
        String startName,
        double startLat,
        double startLng,
        double lengthKm,
        int upMin,
        int downMin,
        String difficulty,
        String risk,
        String source
    ) {
    }
    public record CourseDetail(Course course, List<Object> path) {
    }

    @GetMapping("/api/mountains")
    public List<Mountain> mountains() {
        return jdbc.query(
            """
                select m.id, m.name, m.sigungu_name, m.height, m.lat, m.lng,
                       (select count(*) from courses c where c.mountain_id = m.id) as course_count
                from mountains m order by m.sigungu_name, m.name
                """,
            (rs, i) -> new Mountain(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("sigungu_name"),
                rs.getDouble("height"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getInt("course_count")
            )
        );
    }

    @GetMapping("/api/mountains/{id}/courses")
    // 산 자체가 없으면 404, 산은 있지만 생성된 코스가 없으면 빈 목록으로 구분한다.
    public List<Course> courses(@PathVariable long id) {
        if (jdbc.queryForObject("select count(*) from mountains where id = ?", Long.class, id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "산을 찾을 수 없습니다.");
        }
        return jdbc.query(
            "select * from courses where mountain_id = ? order by length_km, id",
            (rs, i) -> course(rs),
            id
        );
    }

    @GetMapping("/api/courses/{id}")
    public CourseDetail detail(@PathVariable long id) {
        return jdbc.query(
            "select * from courses where id = ?",
            (rs, i) ->
            new CourseDetail(
                course(rs),
                JsonParserFactory.getJsonParser().parseList(rs.getString("path"))
            ),
            id
        )
        .stream().findFirst().orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "코스를 찾을 수 없습니다."));
    }

    private static Course course(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Course(
            rs.getLong("id"),
            rs.getLong("mountain_id"),
            rs.getString("name"),
            rs.getString("start_name"),
            rs.getDouble("start_lat"),
            rs.getDouble("start_lng"),
            rs.getDouble("length_km"),
            rs.getInt("up_min"),
            rs.getInt("down_min"),
            rs.getString("difficulty"),
            rs.getString("risk"),
            rs.getString("source")
        );
    }
}
