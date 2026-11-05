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
