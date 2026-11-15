package com.ggmount.global.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class ProductionFlywayGuard {
    private static final String REGULAR = "classpath:db/migration";
    private static final String COMPAT = "classpath:db/compat/rds-v9001";
    private static final Map<MigrationVersion, Integer> REQUIRED = Map.of(
        MigrationVersion.fromVersion("9000"), 811898634,
        MigrationVersion.fromVersion("9001"), -1880174823);

    static boolean isProduction(Environment environment) {
        return environment.matchesProfiles("prod");
    }

    static void checkEnvironment(Environment environment) {
        boolean prod = isProduction(environment);
        boolean compat = environment.matchesProfiles("prod-rds-v9001");
        require(!compat || prod, "호환 프로필에는 prod가 필요합니다.");
        if (!prod) return;
        require(!environment.matchesProfiles("local", "mobile"), "운영과 로컬·모바일 프로필을 혼용할 수 없습니다.");
        if (compat) {
            require(Set.copyOf(Arrays.asList(environment.getActiveProfiles()))
                .equals(Set.of("prod", "prod-rds-v9001")), "운영 호환 프로필 조합이 올바르지 않습니다.");
        }
        require(environment.getProperty("spring.flyway.enabled", Boolean.class, true), "운영 Flyway를 비활성화할 수 없습니다.");
        require(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true), "운영 마이그레이션 검증이 필요합니다.");
        require(!environment.getProperty("spring.flyway.out-of-order", Boolean.class, false), "순서 우회 실행은 허용되지 않습니다.");
    }

    public void check(Flyway flyway, boolean compat) {
        try {
            Configuration config = flyway.getConfiguration();
            checkConfiguration(config, compat);
            checkResources(config);
            Map<MigrationVersion, MigrationInfo> catalog = checkCatalog(flyway.info().all(), compat);
            if (compat) checkHistory(config, catalog);
        } catch (Exception exception) {
            // 연결 주소나 인증 정보를 포함할 수 있는 원인 예외는 외부에 전달하지 않습니다.
            throw new IllegalStateException("운영 Flyway 사전 검증에 실패하여 실행을 중단합니다.");
        }
    }

    void checkConfiguration(Configuration config, boolean compat) {
        var locations = Arrays.stream(config.getLocations()).map(Object::toString).toList();
        Set<String> expected = compat ? Set.of(REGULAR, COMPAT) : Set.of(REGULAR);
        require(locations.size() == expected.size() && new HashSet<>(locations).equals(expected),
            "운영 Flyway 경로가 허용 목록과 다릅니다.");
        require(config.isValidateOnMigrate() && !config.isOutOfOrder()
            && !config.isBaselineOnMigrate() && !config.isSkipExecutingMigrations()
            && !config.isSkipDefaultResolvers() && config.isCleanDisabled(), "운영 Flyway 우회 설정은 허용되지 않습니다.");
        require(MigrationVersion.LATEST.equals(config.getTarget())
            && (config.getCherryPick() == null || config.getCherryPick().length == 0),
            "운영 마이그레이션 대상 우회는 허용되지 않습니다.");
        require(config.getResolvers().length == 0 && config.getJavaMigrations().length == 0
            && config.getCallbacks().length == 0, "운영 사용자 정의 마이그레이션과 콜백은 허용되지 않습니다.");
        require(config.getInitSql() == null || config.getInitSql().isBlank(),
            "운영 연결 초기화 SQL은 허용되지 않습니다.");
        require(config.getErrorOverrides().length == 0 && config.getDryRunOutput() == null,
            "운영 오류 무시 및 실행 대체 설정은 허용되지 않습니다.");
        require(config.getCallbackLocations().length == 0
            && config.getResourceProvider() == null && config.getJavaMigrationClassProvider() == null,
            "운영 리소스 검색 우회는 허용되지 않습니다.");
        require("V".equals(config.getSqlMigrationPrefix()) && "__".equals(config.getSqlMigrationSeparator())
            && Arrays.equals(new String[]{".sql"}, config.getSqlMigrationSuffixes()),
            "운영 SQL 파일명 규칙을 변경할 수 없습니다.");
        require(config.getIgnoreMigrationPatterns().length == 0
            || (config.getIgnoreMigrationPatterns().length == 1
                && "*:future".equals(config.getIgnoreMigrationPatterns()[0].toString())),
            "운영 검증 생략 패턴이 허용되지 않습니다.");
    }

    private void checkResources(Configuration config) throws Exception {
        // info()에도 SQL 콜백이 연결될 수 있으므로 DB 접근 전에 리소스를 검사합니다.
        var resolver = new PathMatchingResourcePatternResolver(config.getClassLoader());
        Set<MigrationVersion> versions = new HashSet<>();
        for (var location : config.getLocations()) {
            String path = location.toString().substring("classpath:".length());
            require(resolver.getResources("classpath*:" + path + "/**/*.class").length == 0,
                "운영 Java 마이그레이션은 별도 검토가 필요합니다.");
            for (var resource : resolver.getResources("classpath*:" + path + "/**/*.sql")) {
                String name = resource.getFilename();
                require(name != null && name.matches("V.+__.+\\.sql"),
                    "운영 SQL 콜백과 반복 마이그레이션은 허용되지 않습니다.");
                var version = MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
                require(versions.add(version), "중복 마이그레이션 리소스입니다.");
                require(!version.equals(MigrationVersion.fromVersion("9002"))
                    && !version.equals(MigrationVersion.fromVersion("9003")), "금지된 테스트 SQL입니다.");
            }
        }
    }

    Map<MigrationVersion, MigrationInfo> checkCatalog(MigrationInfo[] migrations, boolean compat) {
        Map<MigrationVersion, MigrationInfo> catalog = new HashMap<>();
        for (MigrationInfo migration : migrations) {
            MigrationVersion version = migration.getVersion();
            require(version != null, "운영 반복 마이그레이션은 별도 검토가 필요합니다.");
            require(!version.equals(MigrationVersion.fromVersion("9002"))
                && !version.equals(MigrationVersion.fromVersion("9003")), "금지된 테스트 마이그레이션이 발견됐습니다.");
            require(catalog.putIfAbsent(version, migration) == null, "중복 마이그레이션 버전입니다.");
            require(migration.isChecksumMatching() && migration.isDescriptionMatching() && migration.isTypeMatching(),
                "적용 이력과 원본이 일치하지 않습니다.");
            if (migration.isApplied()) {
                require("SUCCESS".equals(migration.getState().name()), "예상하지 못한 적용 이력입니다.");
            }
        }
        for (var entry : REQUIRED.entrySet()) {
            if (compat) {
                require(catalog.containsKey(entry.getKey())
                    && entry.getValue().equals(catalog.get(entry.getKey()).getResolvedChecksum()),
                    "호환 마이그레이션 원본이 없거나 다릅니다.");
            } else {
                require(!catalog.containsKey(entry.getKey()), "기존 RDS에는 호환 모드가 필요합니다.");
            }
        }
        return catalog;
    }

    void checkHistory(Configuration config, Map<MigrationVersion, MigrationInfo> catalog) throws Exception {
        // JPA를 사용하지 않고 실제 Flyway 연결과 스키마·이력 테이블을 사용합니다.
        try (Connection connection = config.getDataSource().getConnection()) {
            connection.setReadOnly(true);
            String schema = config.getDefaultSchema();
            if (schema == null && config.getSchemas().length > 0) schema = config.getSchemas()[0];
            if (schema == null) schema = connection.getSchema();
            require(schema != null && !schema.isBlank(), "이력 스키마를 확인할 수 없습니다.");
            String quote = connection.getMetaData().getIdentifierQuoteString().trim();
            require(!quote.isEmpty(), "이력 식별자 인용을 지원하지 않습니다.");
            String table = quoted(schema, quote) + "." + quoted(config.getTable(), quote);
            Map<MigrationVersion, Integer> history = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT " + quoted("version", quote) + ","
                     + quoted("checksum", quote) + "," + quoted("success", quote) + " FROM " + table)) {
                while (rows.next()) {
                    require(rows.getBoolean(3) && !rows.wasNull(), "실패한 마이그레이션 이력입니다.");
                    String rawVersion = rows.getString(1);
                    require(rawVersion != null, "예상하지 못한 이력 형식입니다.");
                    MigrationVersion version = MigrationVersion.fromVersion(rawVersion);
                    int checksum = rows.getInt(2);
                    require(!rows.wasNull(), "이력 체크섬이 없습니다.");
                    require(history.putIfAbsent(version, checksum) == null, "중복 적용 이력입니다.");
                    MigrationInfo resolved = catalog.get(version);
                    require(resolved != null && Integer.valueOf(checksum).equals(resolved.getResolvedChecksum()),
                        "이력과 마이그레이션 원본이 일치하지 않습니다.");
                }
            }
            require(!history.isEmpty() && history.entrySet().containsAll(REQUIRED.entrySet()),
                "기존 RDS 호환 이력을 확인할 수 없습니다.");
            require(!history.containsKey(MigrationVersion.fromVersion("9002"))
                && !history.containsKey(MigrationVersion.fromVersion("9003")), "금지된 적용 이력입니다.");
        }
    }

    private static String quoted(String value, String quote) {
        require(value != null && !value.isBlank(), "이력 식별자가 비어 있습니다.");
        return quote + value.replace(quote, quote + quote) + quote;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
