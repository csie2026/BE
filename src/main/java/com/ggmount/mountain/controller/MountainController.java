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
// 보안 설정에 따라 인증 후 탐색 데이터를 조회하며, 회원별 산행 활동과 별도인 산·코스 테이블만 읽는다.
// 데이터를 수정하거나 거리·소요 시간을 새로 계산하지 않으며, 이 컨트롤러에서 캐시 헤더를 별도로 지정하지 않는다.
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

    // 검색·페이지 입력 없이 모든 산을 시군구명·산 이름 순으로 반환하고, 각 산에 연결된 코스 수를 함께 집계한다.
    // 산 상세 전용 API는 없으며 산 목록이 비어 있으면 빈 목록을 반환한다.
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
    // id는 long으로 변환해 산 ID 조건에 바인딩한다. 별도 양수 검증은 없으며 코스는 길이·ID 오름차순으로 반환한다.
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

    // 코스 ID로 단일 코스와 경로를 조회하며, 경로 문자열은 JSON 목록으로 변환한다. 해당 코스가 없으면 404다.
    // 데이터 접근·경로 파싱 예외는 여기서 변환하지 않고 전파하며, 상태 지정 예외는 공통 처리기가 응답한다.
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

    // 목록·상세의 공통 코스 정보로 DB 값을 옮긴다. 길이는 km, 오름·내림 소요 시간은 분 단위 필드다.
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
