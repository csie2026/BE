package com.ggmount;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class MemberJoinedAtMigrationTests {
    @Test
    void upgradePreservesUnknownLegacyDateAndRecordsOnlyNewMemberships() throws Exception {
        String url = "jdbc:h2:mem:member_joined_at_upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("7").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("insert into members(provider,provider_id,nickname) values('google','legacy-date','Original name')");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select nickname,joined_at from members where provider_id='legacy-date'")) {
                assertTrue(rows.next()); assertEquals("Original name", rows.getString(1)); assertNull(rows.getObject(2));
            }
            sql.executeUpdate("insert into members(provider,provider_id) values('kakao','new-date')");
            try (var rows = sql.executeQuery("select joined_at from members where provider_id='new-date'")) {
                assertTrue(rows.next()); assertNotNull(rows.getObject(1));
            }
            // A later login/profile change must not turn an unknown legacy date into today's date.
            sql.executeUpdate("update members set nickname='Changed name' where provider_id='legacy-date'");
            try (var rows = sql.executeQuery("select joined_at from members where provider_id='legacy-date'")) {
                assertTrue(rows.next()); assertNull(rows.getObject(1));
            }
        }
    }
}
