> **2차 통합 후 적용 기준 (2026-11-05)**
> 아래 초기 작업 기록의 integrity V5는 현재 `V7__hiking_activity_integrity.sql`로 이전했습니다.
> 사용자 DB에서 이미 적용된 점수 V5와 목표 V6는 원문·체크섬·이력을 유지합니다.
> 현재 점수 연동과 통합 검증 범위는 문서 하단의 "2차 통합"을 확인하세요.

# Hiking activity integrity (issue #18)

`POST /api/users/me/hiking-records` keeps session authentication, CSRF, and existing summary fields. New activities additionally require `gpsSegments` (an explicit empty array is valid):

```json
{
  "mountainId": 1,
  "courseId": null,
  "startedAt": "2026-01-01T01:00:00.000Z",
  "endedAt": "2026-01-01T02:00:00.000Z",
  "distanceMeters": 2000,
  "elapsedMs": 3600000,
  "completed": true,
  "clientRequestId": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
  "gpsSegments": [[{
    "lat": 37.0,
    "lng": 127.0,
    "measuredAt": "2026-01-01T01:30:00.000Z",
    "accuracyMeters": 12
  }]]
}
```

Each sample must have finite coordinates in latitude/longitude range, finite nonnegative accuracy, and an Instant within the original activity interval. Sample time cannot decrease, including across segments. Null segments/points and malformed nesting are rejected. Limits: 10,000 total samples, 10,000 segments, and 2 MiB of request body, including unknown-length/chunked bodies. Empty segments do not establish completion; there is no minimum distance, duration or sample count for saving an activity.

Completion uses the mountain's DB latitude/longitude and the same 6,371,000 m spherical distance calculation as FE. A sample with accuracy <= 50 m and distance <= 30 m establishes completion. Samples with poorer accuracy do not count. No 15-second collection-age check is applied at upload time. The submitted `completed` is an expectation: a differing server result still saves with HTTP 200. This checks submitted evidence; it does not attest that a physical device generated those coordinates.

The response retains existing fields and adds `gpsValidationVersion`: 1 for checked activities, null for preserved legacy activities. `completed` is authoritative. Distances and elapsed time remain client-reported subject to existing validation; rankings/scores are unchanged.

## Retries and duplicates

- UUIDs remain lowercase strings and summary instants use existing pgjdbc-compatible microsecond rounding.
- Verified UUID retries compare a versioned SHA-256 request fingerprint. They return the saved result without recomputing completion.
- Fingerprint V1 writes typed big-endian binary fields through DataOutputStream: version (short), mountain ID (long), course presence (boolean) and optional ID (long), normalized start/end (epoch seconds long + nanos int), distance (double), elapsed (long), expected completed (boolean), segment count (int), each segment length (int), and every sample's latitude/longitude (double), exact measurement Instant, accuracy (double). Java double parsing canonicalizes numeric JSON spellings; signed zero remains distinct. UUID casing, JSON property order and whitespace are not part of the fingerprint. SHA-256 is lowercase hexadecimal. Freeze evidence before first submission; reordering/changing even an excluded sample changes the fingerprint.
- Legacy activities have all three integrity metadata fields null. Their original summary-based retry behavior remains, including requests without GPS. They are not reassessed or rewritten.
- A different UUID with identical `(member_id, mountain_id, normalized started_at, normalized ended_at)` returns 409. Course, distance or completion changes do not avoid this check. Different-time repeat hikes remain allowed.
- Conflict bodies distinguish `client_request_conflict` from `duplicate_hiking_activity`. Body limit failures return HTTP 413 / `hiking_request_too_large`; malformed evidence returns 400 with a generic message that does not echo coordinates.

V5 keeps V4 unchanged, adds the session UNIQUE and nullable legacy integrity metadata with a consistency CHECK. Defaults apply only to new inserts. Old writers lacking a fingerprint cannot silently create unchecked activities. PostgreSQL UNIQUE errors are translated after the service transaction has rolled back. No raw GPS path is persisted or logged by these handlers.

## Deployment and FE #15

Before applying V5 to each target database, inspect duplicates read-only:

```sql
SELECT member_id, mountain_id, started_at, ended_at, count(*)
FROM hiking_activities
GROUP BY member_id, mountain_id, started_at, ended_at
HAVING count(*) > 1;
```

UNIQUE creation itself checks all rows and fails on duplicates. Flyway rolls back V5 on PostgreSQL. Do not delete, merge or repair history automatically. Coordinate application rollout: V5's defaults/CHECK intentionally reject old writers without integrity metadata. Existing activity IDs, completion values and journal links are preserved.

FE must send frozen evidence on every retry and accept server `completed` even when its expectation differs. Change its exact completion response comparison. Keep a single compact evidence copy in recovery storage; serializing GPS objects twice exceeds existing 1,000,000-byte recovery limits. The 10,000-sample limit and byte limit are independent. Measure UTF-8 bytes of both recovery and outgoing JSON; do not truncate samples silently. Endpoint JSON is allowed up to 2 MiB, separate from image/multipart limits.

Version old recovery drafts explicitly. Paused drafts with coordinates can be converted while retaining UUID and sample data. A completed old draft whose coordinates were discarded cannot be converted to `gpsSegments: []`: if already saved, replay its legacy UUID; if unsaved, preserve it and explain that it cannot satisfy the new API. FE was not modified in this change.

## Verification

Default H2 tests run with `mvn package`. PostgreSQL regression tests are opt-in and commit only to a dedicated local database:

```text
mvn package -Dtopeak.postgres.test-url=jdbc:postgresql://127.0.0.1:55439/uuid_idempotency_regression_integrity -Dtopeak.postgres.test-user=uuid_test_admin
```

The URL guard runs before application/Flyway startup and accepts only local databases named `uuid_idempotency_regression` with an optional lowercase suffix. Do not use a shared or operational database. Tests retain their generated rows and isolated migration schemas. An optional password comes from `topeak.postgres.test-password`.

Coverage includes server completion/expectation mismatch, accuracy/radius boundaries, invalid/missing/empty evidence, resource limits, canonical retries, legacy compatibility, different-UUID collisions, 8 independent blocked PostgreSQL backends, UNIQUE failure rollback, V4 upgrade preservation, duplicate-history migration failure, and real chunked HTTP body rejection.

Residual limitations: client-generated coordinates and activity times can be fabricated; time changes can evade the defined session key. Stored fingerprints cannot reconstruct original paths for later review. GPS evidence must stay out of external request/body logging and observability payload capture as well.

## Execution result (2026-11-05)

The full Maven package command above (with `-o`) completed successfully on PostgreSQL 14.24: 70 tests discovered, 69 passed, 0 failures/errors, 1 external weather API test skipped because its required environment variable was absent. PostgreSQL tests: 25 passed; request-size/privacy tests: 3 passed; actual chunked HTTP test: 1 passed. Existing OAuth, image, profile/journal, seed, migration and weather-rule tests passed as well. Build evidence is in `target/uuid-pg-verification/integrity-final-build.log` and `target/surefire-reports/`.

Only the isolated `uuid_idempotency_regression_integrity` database and its test schemas received PostgreSQL writes. The configured shared `ggmount` DB was inspected read-only after verification: 0 activities, 0 duplicate groups, latest successful Flyway version still 4. The dedicated test server was stopped; test data remains under target for review. FE and V4 were unchanged. No commit, push or PR was made.

Changed production files (relative to BE):

- `src/main/java/com/ggmount/hiking/dto/HikingActivityRequest.java`
- `src/main/java/com/ggmount/hiking/dto/GpsPointRequest.java`
- `src/main/java/com/ggmount/hiking/dto/HikingActivityResponse.java`
- `src/main/java/com/ggmount/hiking/domain/HikingActivity.java`
- `src/main/java/com/ggmount/hiking/repository/HikingActivityRepository.java`
- `src/main/java/com/ggmount/hiking/service/HikingActivityService.java`
- `src/main/java/com/ggmount/hiking/service/GpsEvidence.java`
- `src/main/java/com/ggmount/hiking/service/HikingRequestFingerprint.java`
- `src/main/java/com/ggmount/hiking/service/HikingActivityConflict.java`
- `src/main/java/com/ggmount/hiking/controller/HikingActivityController.java`
- `src/main/java/com/ggmount/hiking/controller/HikingActivityBodyLimitFilter.java`
- `src/main/java/com/ggmount/global/exception/GlobalExceptionHandler.java`
- `src/main/resources/db/migration/V5__hiking_activity_integrity.sql`

Changed/new tests: `HikingActivityPostgresTests`, `HikingActivityBodyLimitTests`, `HikingActivityHttpLimitTests`, `JournalMigrationTests`, `ProfileJournalIntegrationTests`. This document records the API and rollout requirements.


## 2차 통합: GPS 검증과 점수 산정 및 FE 계약

- 통합 FE는 전체 `gpsSegments`와 선택적 점수 표본 `gpsEvidence`를 함께 보냅니다.
- `gpsSegments`는 신규 활동에 필수이며 기존처럼 명시적 빈 배열은 저장할 수 있습니다.
  선택적 표본만으로 경로 검증을 우회하거나 점수를 받을 수 없습니다.
- 검증된 전체 경로에서 정확도50m 이내 표본 존재 여부로 `gps_recorded`를 설정합니다.
  따라서 팀장 FE의 경로만 있는 요청도 정상 점수 산정 대상이 될 수 있습니다.
- 정상 판정은 DB 산 좌표와 전체 경로의30m/50m 기준을 사용합니다.
  방문/완등 점수는 저장된 서버 판정과500m/일시정지 제외20분/회원별 KST 하루1건 정책을 사용합니다.
- 응답에는 `gpsValidationVersion`과 `scoring`을 모두 포함합니다. FE의 요청 expected completed는
  지문에 유지하며 실제 서버 completed가 다르더라도 정상 응답으로 처리합니다.
- 팀장의 typed fingerprint V1 형식은 변경하지 않았습니다. 추가된 선택적 표본은 기존
  `gps_evidence_hash`로 별도 비교하여 재시도 시 변경되지 않도록 합니다.
- 종료된 구버전 요청은 원래 UUID/본문을 유지합니다. 이미 저장된 legacy 활동만 기존 요약과
  저장된 표본 해시에 따라 조회할 수 있으며 신규 경로 없는 저장은 허용하지 않습니다.
- 회원 잠금/정규화/동일 세션 UNIQUE/본문2MiB 제한/로그 redaction을 유지합니다.
- 저장과 점수 원장/누적점수 갱신은 같은 트랜잭션입니다. 실패한 중복 요청은 점수를 변경하지 않습니다.
- 기존 활동·일지·점수·목표는 V7 DDL로 삭제하거나 초기화하지 않습니다.

### DB 버전 결정 근거

사용자 DB 읽기전용 확인 결과: 점수 V5(-1357911174), 목표 V6(1026093581) 적용 완료,
활동1건/동일세션 중복그룹0건, integrity 메타데이터 미적용.
따라서 이력은 수정하지 않고 팀장 integrity SQL 원문을 V7로 이동합니다.
다른 DB에 초기 integrity V5가 이미 적용되어 있다면 이 배포 경로와 같다고 가정하지 말고
그 DB의 이력을 먼저 확인해야 합니다. 기존 DB를 초기화하거나 Flyway repair로 덮지 않습니다.

### 검증 범위

- 통합 FE가 실제 생성한555.974633m/20분/13표본 요청을 순수 BE 경로판정/지문/정규화/
  점수정책에 연결한17개 검사 통과. 클라이언트false→서버true→270점 및 재방문170점 확인.
- H2 2.5.252에서 실제 V1~V6 SQL→V7 SQL 적용 및9개 데이터 보존/기본값/UNIQUE 검사 통과.
- 변경 Java 구문 검사 수행. 이들은 전체 Spring·Bean Validation·Flyway·PostgreSQL 실행의 대체가 아닙니다.
- 기존 점수/회원/랭킹/목표/이미지 테스트를 유지하고, GPS 검증+점수 교차검증8개를 추가했습니다.
- 개발 환경 Java17/의존성 접근 제한 때문에 Spring 전체 실행은 사용자 Java21 결과 확인 대기입니다.
- 최종87개 기본 통합/회귀 대상과 opt-in PostgreSQL25개는 서로 구분해 기록합니다.
  PostgreSQL 테스트는 전용 로컬 DB에서만 실행하며 현재 사용자 DB를 테스트 DB로 쓰지 않습니다.
