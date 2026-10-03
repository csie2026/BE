package com.ggmount;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class JournalMigrationTests {
    @Test
    void upgradingPreservesAnUnlinkedJournalAndAddsUniqueActivityLink() throws Exception {
        String url = "jdbc:h2:mem:journal_upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("3.1").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("insert into members(id,provider,provider_id,nickname,profile_completed) values(1,'google','legacy','Old member',true)");
            sql.executeUpdate("insert into hiking_records(id,member_id,mountain_name,title,content,hiking_date,is_public) values(1,1,'Original mountain','Old title','Original text','2019-01-01',true)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select mountain_name,content,hiking_activity_id from hiking_records where id=1")) {
                assertTrue(rows.next());
                assertEquals("Original mountain", rows.getString(1));
                assertEquals("Original text", rows.getString(2));
                assertNull(rows.getObject(3));
            }
            sql.executeUpdate("insert into hiking_activities(id,member_id,mountain_id,mountain_name,started_at,ended_at,distance_meters,elapsed_ms,completed,client_request_id) values(1,1,1,'Actual mountain','2020-01-01 01:00:00+00','2020-01-01 02:00:00+00',1000,3600000,true,'00000000-0000-0000-0000-000000000001')");
            sql.executeUpdate("update hiking_records set hiking_activity_id=1 where id=1");
            assertThrows(java.sql.SQLException.class, () -> sql.executeUpdate("insert into hiking_records(member_id,mountain_name,title,content,hiking_date,is_public,hiking_activity_id) values(1,'Actual mountain','Duplicate','text','2020-01-01',true,1)"));
        }
    }
}
