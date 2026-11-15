# 산행 활동 무결성·GPS 검증

FE·BE 공통 계약과 과거 롤아웃/지문 상세 원본은 [DOCS의 산행 무결성](https://github.com/csie2026/DOCS/blob/dev/hiking-activity-integrity.md), HTTP 모델은 [API_SPEC.md](https://github.com/csie2026/DOCS/blob/dev/API_SPEC.md), 현재 v2/과거 v1·통계 기준은 [SCORE_POLICY.md](https://github.com/csie2026/DOCS/blob/dev/SCORE_POLICY.md)에서 관리합니다. 기존 링크 호환을 위해 이 경로를 유지합니다.

신규 저장은 전체 GPS 경로가 필요합니다. v2 일일 제한은 없으며 v1 활동/일일 인증/공개 보너스 금액·버전은 보존합니다. 산행 횟수는 점수와 별도 기준입니다.

## BE 테스트

```powershell
mvn.cmd verify
# 아래는 새로 생성·용도를 검증한 로컬 전용 DB에만 지정합니다.
mvn.cmd '-Dtest=HikingActivityPostgresTests' `
  '-Dtopeak.postgres.test-url=jdbc:postgresql://127.0.0.1:55439/uuid_idempotency_regression_local' `
  '-Dtopeak.postgres.test-user=<전용계정>' test
```

비밀번호는 명령줄/문서에 적지 않고 TOPEAK_TEST_PASSWORD로 공급합니다. URL은 loopback 및 uuid_idempotency_regression[_소문자/숫자 suffix]만 허용합니다. 이름/주소만으로 전용 DB라고 판정하지 말고 실제 서버·데이터 디렉터리·DB 소유자·용도·초기 상태를 확인합니다. 운영·개발·기존 보존 DB에는 실행하지 않습니다.

이 suite는 실제 커밋과 INSERT/UPDATE/DELETE, migration 스키마 생성 및 동시 연결을 수행하고 생성 데이터를 남깁니다. 기존 DB를 clean/초기화하거나 fixture를 임의 삭제하지 않습니다. 새로 만든 전용 서버만 작업 후 중지하고 데이터 정리 여부를 보고합니다.

2026-11-13 BE bd10265의 선행 표준 javac 검증: 기존 PG32개(비활성 집계31개)와 별도 외부 보조4개 통과, 전체216개 중215통과·날씨1스킵. H2 고정 suite가 모두 PG로 전환되는 것은 아닙니다. Windows 상위 경로 조회 제한은 사용 중이지 않은 임시 드라이브를 실행 세션에서만 연결하는 방식으로 우회했고, 검증 후 해제했습니다. JDK/Wrapper/POM/ACL을 교체하지 않았습니다.
