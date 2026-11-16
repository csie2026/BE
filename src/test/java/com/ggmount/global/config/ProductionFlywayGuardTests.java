package com.ggmount.global.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 운영 프로필에서 마이그레이션 경로·카탈로그·기존 이력 우회를 시작 전에 차단하는지 검증한다.
class ProductionFlywayGuardTests {
    private final ProductionFlywayGuard guard = new ProductionFlywayGuard();

    private MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private FluentConfiguration configuration(boolean compat) {
        return Flyway.configure().locations(compat
            ? new String[]{"classpath:db/migration", "classpath:db/compat/rds-v9001"}
            : new String[]{"classpath:db/migration"});
    }

    private MigrationInfo migration(String version, int checksum) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(migration.getResolvedChecksum()).thenReturn(checksum);
        when(migration.isChecksumMatching()).thenReturn(true);
        when(migration.isDescriptionMatching()).thenReturn(true);
        when(migration.isTypeMatching()).thenReturn(true);
        when(migration.isApplied()).thenReturn(true);
        when(migration.getState()).thenReturn(MigrationState.SUCCESS);
        return migration;
    }

    @Test void allowedProfilesAndNonProductionRemainUsable() {
        assertDoesNotThrow(() -> ProductionFlywayGuard.checkEnvironment(environment("prod")));
        assertDoesNotThrow(() -> ProductionFlywayGuard.checkEnvironment(environment("prod", "prod-rds-v9001")));
        assertDoesNotThrow(() -> ProductionFlywayGuard.checkEnvironment(environment("local", "mobile")
            .withProperty("spring.flyway.enabled", "false")));
    }

    @Test void invalidProfileCombinationsAreRejected() {
        for (String[] profiles : new String[][]{{"prod", "local"}, {"prod", "mobile"},
            {"prod-rds-v9001"}, {"prod", "prod-rds-v9001", "extra"}}) {
            assertThrows(IllegalStateException.class, () -> ProductionFlywayGuard.checkEnvironment(environment(profiles)));
        }
    }

    @Test void disabledFlywayIsRejectedEvenWithoutFlywayBean() {
        new ApplicationContextRunner().withUserConfiguration(ProductionFlywayConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod", "spring.flyway.enabled=false")
            .run(context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure);
                while (failure.getCause() != null) failure = failure.getCause();
                assertEquals("운영 Flyway를 비활성화할 수 없습니다.", failure.getMessage());
            });
    }

    @Test void unsafeEnvironmentSwitchesAreRejected() {
        assertThrows(IllegalStateException.class, () -> ProductionFlywayGuard.checkEnvironment(
            environment("prod").withProperty("spring.flyway.validate-on-migrate", "false")));
        assertThrows(IllegalStateException.class, () -> ProductionFlywayGuard.checkEnvironment(
            environment("prod").withProperty("spring.flyway.out-of-order", "true")));
    }

    @Test void expectedLocationsAreAccepted() {
        assertDoesNotThrow(() -> guard.checkConfiguration(configuration(false), false));
        assertDoesNotThrow(() -> guard.checkConfiguration(configuration(true), true));
    }

    @ParameterizedTest
    @ValueSource(strings={"classpath:db", "classpath:db/local-test", "filesystem:db/migration"})
    void unsafeLocationsAreRejected(String location) {
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).locations(location), false));
    }

    @Test void duplicatedAndIncompleteLocationsAreRejected() {
        var config = mock(org.flywaydb.core.api.configuration.Configuration.class);
        when(config.getLocations()).thenReturn(new Location[]{new Location("classpath:db/migration"), new Location("classpath:db/migration")});
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(config, false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false), true));
    }

    @Test void finalConfigurationOverridesAreRejected() {
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).outOfOrder(true), false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).validateOnMigrate(false), false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).baselineOnMigrate(true), false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).skipExecutingMigrations(true), false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).ignoreMigrationPatterns("*:missing"), false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).target("9001"), false));
        assertThrows(IllegalStateException.class, () -> guard.checkConfiguration(configuration(false).initSql("SELECT 1"), false));
    }

    @ParameterizedTest
    @ValueSource(strings={"9002", "9003", "9002.0"})
    void forbiddenVersionsAreRejected(String version) {
        assertThrows(IllegalStateException.class, () -> guard.checkCatalog(new MigrationInfo[]{migration(version, 1)}, true));
    }

    @Test void duplicateVersionsAreRejected() {
        assertThrows(IllegalStateException.class, () -> guard.checkCatalog(
            new MigrationInfo[]{migration("1", 1), migration("1.0", 1)}, false));
    }

    @Test void missingOrChangedCompatibilityOriginalsAreRejected() {
        assertThrows(IllegalStateException.class, () -> guard.checkCatalog(new MigrationInfo[0], true));
        assertThrows(IllegalStateException.class, () -> guard.checkCatalog(
            new MigrationInfo[]{migration("9000", 1), migration("9001", -1880174823)}, true));
    }

    @Test void failedAndMismatchedAppliedMigrationsAreRejected() {
        MigrationInfo failed = migration("1", 1);
        when(failed.getState()).thenReturn(MigrationState.FAILED);
        assertThrows(IllegalStateException.class, () -> guard.checkCatalog(new MigrationInfo[]{failed}, false));
        MigrationInfo mismatch = migration("2", 1);
        when(mismatch.isChecksumMatching()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> guard.checkCatalog(new MigrationInfo[]{mismatch}, false));
    }

    // JDBC 전체를 모의 처리하여 외부 DB 연결 없이 실제 읽기 경로를 검사한다.
    private FluentConfiguration historyConfiguration(Object[][] rows) throws Exception {
        DataSource datasource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(datasource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");
        when(connection.getSchema()).thenReturn("actual_schema");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            assertEquals("SELECT \"version\",\"checksum\",\"success\" FROM \"actual_schema\".\"custom_history\"", invocation.getArgument(0));
            verify(connection).setReadOnly(true);
            return result;
        });
        int[] cursor = {-1};
        when(result.next()).thenAnswer(invocation -> ++cursor[0] < rows.length);
        when(result.getString(1)).thenAnswer(invocation -> rows[cursor[0]][0]);
        when(result.getInt(2)).thenAnswer(invocation -> rows[cursor[0]][1]);
        when(result.getBoolean(3)).thenAnswer(invocation -> rows[cursor[0]][2]);
        return configuration(true).dataSource(datasource).table("custom_history");
    }

    private Map<MigrationVersion, MigrationInfo> compatibleCatalog() {
        return guard.checkCatalog(new MigrationInfo[]{migration("9000", 811898634), migration("9001", -1880174823)}, true);
    }

    @Test void exactHistoryIsAcceptedUsingActualDatasourceSchemaAndTable() throws Exception {
        guard.checkHistory(historyConfiguration(new Object[][]{{"9000", 811898634, true}, {"9001", -1880174823, true}}), compatibleCatalog());
    }

    @Test void invalidHistoriesAreRejected() throws Exception {
        for (Object[][] rows : new Object[][][]{
            {}, {{"9000", 811898634, true}},
            {{"9000", 0, true}, {"9001", -1880174823, true}},
            {{"9000", 811898634, false}, {"9001", -1880174823, true}},
            {{"9000", 811898634, true}, {"9001", -1880174823, true}, {"9002", 1, true}},
            {{"9000", 811898634, true}, {"9001", -1880174823, true}, {"9003", 1, true}}}) {
            var config = historyConfiguration(rows);
            assertThrows(IllegalStateException.class, () -> guard.checkHistory(config, compatibleCatalog()));
        }
    }

    @Test void missingTableAndUnavailableDatasourceStopWithoutExposingSecrets() throws Exception {
        var config = configuration(true).dataSource(mock(DataSource.class));
        when(config.getDataSource().getConnection()).thenThrow(new java.sql.SQLException("비밀 JDBC 정보"));
        Flyway flyway = mock(Flyway.class);
        when(flyway.getConfiguration()).thenReturn(config);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        var catalog = compatibleCatalog().values().toArray(MigrationInfo[]::new);
        when(info.all()).thenReturn(catalog);
        var exception = assertThrows(IllegalStateException.class, () -> guard.check(flyway, true));
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("JDBC"));
    }

    @Test void strategyCallsMigrateOnlyAfterSuccessfulGuard() throws Exception {
        var environment = environment("prod");
        var strategy = new ProductionFlywayConfiguration().productionFlywayMigrationStrategy(environment);
        Flyway flyway = mock(Flyway.class);
        when(flyway.getConfiguration()).thenReturn(configuration(false));
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        var catalog = new MigrationInfo[]{migration("1", 1)};
        when(info.all()).thenReturn(catalog);
        strategy.migrate(flyway);
        verify(flyway).migrate();
        reset(flyway);
        when(flyway.getConfiguration()).thenReturn(configuration(false).locations("classpath:db"));
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(flyway, never()).migrate();
    }

    @Test void compatibilityStrategyRequiresHistoryBeforeMigration() throws Exception {
        var strategy = new ProductionFlywayConfiguration().productionFlywayMigrationStrategy(
            environment("prod", "prod-rds-v9001"));
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        var catalog = compatibleCatalog().values().toArray(MigrationInfo[]::new);
        when(flyway.info()).thenReturn(info);
        when(info.all()).thenReturn(catalog);
        var empty = historyConfiguration(new Object[][]{});
        when(flyway.getConfiguration()).thenReturn(empty);
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(flyway, never()).migrate();
        var valid = historyConfiguration(new Object[][]{{"9000", 811898634, true}, {"9001", -1880174823, true}});
        when(flyway.getConfiguration()).thenReturn(valid);
        strategy.migrate(flyway);
        verify(flyway).migrate();
    }

    @Test void missingHistoryTableIsRejectedBeforeMigration() throws Exception {
        var config = historyConfiguration(new Object[][]{});
        var connection = config.getDataSource().getConnection();
        when(connection.createStatement()).thenThrow(new java.sql.SQLException("이력 테이블 없음"));
        assertThrows(java.sql.SQLException.class, () -> guard.checkHistory(config, compatibleCatalog()));
    }

    @Test void flywayPublicApiResolvesUnmodifiedCompatibilitySqlWithoutMigration() {
        Flyway flyway = configuration(true)
            .dataSource("jdbc:h2:mem:guard_catalog;DB_CLOSE_DELAY=-1", "sa", "").load();
        var catalog = guard.checkCatalog(flyway.info().all(), true);
        assertEquals(811898634, catalog.get(MigrationVersion.fromVersion("9000")).getResolvedChecksum());
        assertEquals(-1880174823, catalog.get(MigrationVersion.fromVersion("9001")).getResolvedChecksum());
    }

    @Test void bootInitializerUsesGuardBeforeCallingMigration() throws Exception {
        Flyway flyway = mock(Flyway.class);
        when(flyway.getConfiguration()).thenReturn(configuration(false).outOfOrder(true));
        var strategy = new ProductionFlywayConfiguration().productionFlywayMigrationStrategy(environment("prod"));
        var initializer = new org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer(flyway, strategy);
        assertThrows(IllegalStateException.class, initializer::afterPropertiesSet);
        verify(flyway, never()).migrate();
        verify(flyway, never()).info();
    }
}
