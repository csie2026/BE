package com.ggmount.hiking.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * app.scoring.rebuild-on-start가 true일 때만 등록되어 애플리케이션 시작 중 실행되는 점수 재구축 실행기다.
 * 설정이 없으면 등록되지 않는다. 점수 계산은 직접 수행하지 않고 회원별 재구축 서비스에 위임한다.
 */
@Component
@ConditionalOnProperty(name = "app.scoring.rebuild-on-start", havingValue = "true")
public class HikingScoreRebuildRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final HikingScoreRebuildService rebuild;
    public HikingScoreRebuildRunner(JdbcTemplate jdbc, HikingScoreRebuildService rebuild) { this.jdbc = jdbc; this.rebuild = rebuild; }
    @Override public void run(ApplicationArguments args) {
        // 실행할 때마다 ID 0 다음부터 시작한다. 진행 위치는 메모리에만 두므로 재시작 시 이미 처리한 회원도 다시 조회한다.
        long last = 0;
        while (true) {
            // 활동 유무·프로필·정책 버전과 무관하게 회원을 ID 오름차순으로 최대 100명씩 조회한다.
            var ids = jdbc.queryForList("select id from members where id>? order by id limit 100", Long.class, last);
            if (ids.isEmpty()) return;
            // 회원별 커밋 성공 후 위치를 전진한다. 실패하면 재시도·건너뛰기 없이 예외를 전파해 실행을 중단한다.
            // 앞서 완료한 회원의 커밋까지 되돌리는 전체 실행 단위 트랜잭션은 없다.
            for (long id : ids) { rebuild.rebuild(id); last = id; }
        }
    }
}
