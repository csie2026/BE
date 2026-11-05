package com.ggmount.hiking.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Opt-in maintenance run after backup; one member transaction at a time, restart-safe. */
@Component
@ConditionalOnProperty(name = "app.scoring.rebuild-on-start", havingValue = "true")
public class HikingScoreRebuildRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final HikingScoreRebuildService rebuild;
    public HikingScoreRebuildRunner(JdbcTemplate jdbc, HikingScoreRebuildService rebuild) { this.jdbc = jdbc; this.rebuild = rebuild; }
    @Override public void run(ApplicationArguments args) {
        long last = 0;
        while (true) {
            var ids = jdbc.queryForList("select id from members where id>? order by id limit 100", Long.class, last);
            if (ids.isEmpty()) return;
            for (long id : ids) { rebuild.rebuild(id); last = id; }
        }
    }
}
