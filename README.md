# ToPeak BE

## 프로젝트 소개

ToPeak은 경기도 산 탐색과 GPS 기반 등산기록, 등산일지, 랭킹을 제공하는 서비스입니다. 이 저장소는 인증, 회원·이미지 관리, 산·코스·날씨 조회, 산행 결과와 일지 저장 및 랭킹 조회를 담당합니다. 서비스명은 ToPeak이며 실제 Java 기본 package는 `com.ggmount`입니다.

## 주요 기능

- Google/Kakao OAuth2 로그인과 DB 회원 생성·갱신, 세션 인증, CSRF 및 로그아웃
- 내 정보, 최초 프로필 설정·수정, 타 사용자 공개 프로필 조회
- 본인 프로필·배경 이미지 조회·업로드·삭제와 이미지 형식·용량 검증
- 경기도 산 목록, 산별 코스 목록, 코스 상세 및 지도 경로 조회
- 기상청 단기예보 기반 산별 날씨·등산 적합도 조회
- FE에서 측정한 산행 결과 저장, 내 등산기록 목록·상세 조회, 요청 ID 기반 중복 저장 방지
- 본인 등산기록 기반 일지 생성, 내/공개/사용자별 공개 일지 조회, 일지 상세·수정·삭제
- 저장된 회원 점수를 기준으로 랭킹 조회

### 현재 구현 범위

실제 등산기록은 `HikingActivity`, 후기 게시글인 등산일지는 기존 `HikingRecord`입니다. 새 일지는 본인의 활동 ID를 받아 서버에서 산과 서울 기준 산행 날짜를 결정합니다. 활동 하나에 일지는 최대 하나이며, 수정 시 활동·산·날짜를 바꿀 수 없습니다. 타인의 기록 이용, 비공개 일지 열람 및 일지 수정·삭제를 제한합니다. 공개 프로필·공개 등산일지·랭킹 조회에도 세션 로그인이 필요합니다.

산행 저장은 클라이언트의 시간·거리·완등 결과를 저장하며 시간 범위, 산·코스 관계와 요청 중복을 검사합니다. 원시 GPS 경로 저장, 서버의 거리 재계산·산행 진위 검증은 구현되지 않았습니다. `members.score` 조회와 랭킹은 구현되어 있지만 실제 활동 기반 점수 자동 산정·갱신은 미구현입니다.

본인 응답은 업로드한 프로필 사진을 우선하고 공개 프로필·랭킹은 OAuth 공급자 사진을 제공합니다. 개인 이미지 API는 본인 전용입니다. 날씨와 등산기록 상세 API는 BE에 구현되어 있으나 현재 FE 미연결입니다.

## 기술 스택

| 기술 | 설정 기준 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring Web MVC / Validation | REST API 및 입력 검증 |
| Spring Security / OAuth2 Client | 세션 기반 OAuth 인증·CSRF |
| Spring Data JPA / JdbcTemplate | 회원·활동·일지와 산·코스 데이터 접근 |
| PostgreSQL | 실행 DB, runtime JDBC 드라이버 |
| Flyway | schema 및 seed migration, PostgreSQL 지원 모듈 |
| Maven | Wrapper 배포 버전 3.9.16 |
| Lombok | 코드 생성 |
| H2 / Spring Boot 테스트 starter | PostgreSQL 모드 통합 테스트 |

Spring 관련 라이브러리와 DB 드라이버 등의 버전은 `pom.xml`의 Spring Boot dependency management를 따릅니다.

## 프로젝트 구조

```text
src/main/java/com/ggmount/
├─ GgmountApplication.java  # 애플리케이션 진입점
├─ global/
│  ├─ auth/oauth/           # 공급자 정보 변환, principal, 로그인 Handler, CSRF
│  ├─ config/               # Security 등 공통 설정
│  └─ exception/            # 입력·HTTP 상태 예외 처리
├─ member/                 # controller/service/repository/domain/dto
├─ hiking/                 # 활동·일지 controller/service/repository/domain/dto
├─ mountain/controller/    # JdbcTemplate 기반 산·코스 조회
├─ ranking/                # controller/service, 저장 점수 랭킹 조회
└─ weather/                # Controller, 기상청 client, cache·판정·응답 모델
src/main/resources/
├─ application.yaml        # 공통 OAuth·JPA·업로드·날씨 설정
├─ application-local.yml   # local DB 연결, .env import
├─ application-prod.yml    # 운영 프로필 파일
└─ db/migration/           # Flyway SQL
src/test/                  # 인증·권한·이미지·활동·일지·migration·날씨 테스트
```

## 주요 API 영역

| 영역 | 역할 |
| --- | --- |
| 인증 | Google/Kakao 로그인·callback, CSRF 조회, 로그아웃 |
| 회원/프로필 | 본인 정보·프로필 수정, 공개 프로필, 본인 이미지 관리 |
| 산/코스 | 산 목록, 산별 코스, 특정 코스와 path |
| 날씨 | 산별 단기예보 및 등산 적합도 |
| 등산기록 | 실제 활동 저장과 본인 목록·상세 |
| 등산일지 | 활동 기반 생성, 조회·공개·수정·삭제 |
| 랭킹 | 저장된 점수 기준 공개 회원 목록 |

산 검색·시군 필터는 전체 목록을 받은 FE에서 처리합니다. 별도 산 상세나 점수 계산 API는 없습니다. 점수는 기존 본인 정보·공개 프로필·랭킹 응답에 포함됩니다.

상세 요청·응답과 권한 정책은 DOCS Repository의 [ToPeak_API명세서.md](https://github.com/csie2026/DOCS/blob/main/ToPeak_API명세서.md)에서 관리합니다.

## 데이터베이스

PostgreSQL을 사용하며 Flyway가 다음 migration을 순서대로 적용합니다.

| 버전 | 내용 |
| --- | --- |
| V1 | 회원, 기존 등산일지 |
| V2 | 산 schema와 seed |
| V3 | 코스 schema와 seed |
| V3.1 | 개인 이미지와 월 목표 테이블 |
| V4 | 실제 활동과 일지 연결 |

V4의 일지 연결 FK는 legacy 데이터 보존을 위해 nullable이며 기존 일지는 삭제하거나 활동에 임의 연결하지 않습니다. 새 일지는 필수 활동 ID와 소유권 검증, UNIQUE 제약을 사용합니다. 일지 삭제는 활동을 보존하므로 같은 활동으로 재작성할 수 있습니다. 월 목표 테이블은 있으나 현재 서버 API는 없습니다.

공통 JPA 설정은 `ddl-auto: validate`, local은 `none`입니다. Hibernate 자동 schema 생성 대신 Flyway SQL로 관리합니다. 위 목록은 저장소의 migration 상태이며 운영 DB에 적용됐다는 의미는 아닙니다.

## 환경 변수

로컬은 `.env.example`을 참고하여 BE 작업 디렉터리의 `.env`에 설정합니다. `.env`는 `.gitignore`에서 제외되며 `local` 프로필에서만 `optional:file:./.env[.properties]`로 읽습니다. 실제 값은 저장소에 올리지 않습니다.

| 이름 | 용도 |
| --- | --- |
| GOOGLE_CLIENT_ID | Google OAuth 클라이언트 ID |
| GOOGLE_CLIENT_SECRET | Google OAuth 클라이언트 secret |
| KAKAO_CLIENT_ID | Kakao 로그인 REST API 키 |
| KAKAO_CLIENT_SECRET | Kakao 로그인 client secret |
| DB_URL | local PostgreSQL JDBC URL |
| DB_USERNAME | local DB 사용자 |
| DB_PASSWORD | local DB 비밀번호 |
| KMA_SERVICE_KEY | 기상청 단기예보 일반인증키(Decoding); 미설정 시 날씨 조회 불가 |
| FRONTEND_URL | 로그인 성공 후 FE 주소; 기본 `http://localhost:5173` |

`FRONTEND_URL`은 공통 설정에서 사용하며 필요하면 `.env`에 추가합니다. `.env`는 Java properties 방식이므로 `KEY=VALUE`로 작성하고 `export`나 값을 감싸는 따옴표를 사용하지 않습니다.

운영에서는 `.env` import 대신 환경변수를 주입합니다. `SPRING_PROFILES_ACTIVE=prod`와 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로 DB 연결을 설정하고 OAuth·날씨·FE 주소 변수도 주입합니다. `DB_*` 참조는 local 설정에만 있습니다.

## 실행 방법

Java 21을 설치하고 PostgreSQL DB와 접근 가능한 계정을 준비합니다. BE 디렉터리에서 다음을 실행합니다.

```powershell
Copy-Item .env.example .env
```

기존 `.env`가 있으면 덮어쓰지 않습니다. DB 및 발급받은 OAuth 설정을 채운 후 실행합니다.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

기본 BE 주소는 `http://localhost:8080`입니다. Maven Wrapper 최초 사용 시 Maven 다운로드가 필요합니다. macOS/Linux에서는 `./mvnw`를 사용합니다. local의 `.env`는 BE 작업 디렉터리 기준으로 읽으므로 IDE 실행에서도 작업 디렉터리와 `local` 프로필을 맞춥니다.

FE 개발 proxy를 사용하는 OAuth callback은 `http://localhost:5173/login/oauth2/code/google`과 `http://localhost:5173/login/oauth2/code/kakao`를 공급자 콘솔에 등록합니다. BE로 직접 로그인한다면 8080 주소를 사용합니다. 코드의 callback은 `{baseUrl}/login/oauth2/code/{registrationId}`이므로 실제 접속 origin과 등록 주소를 일치시킵니다.

인증은 서버 세션이며 JWT를 발급하지 않습니다. FE는 세션 cookie와 `/api/csrf`에서 받은 토큰을 변경 요청에 전달합니다. 로그인 성공 시 `FRONTEND_URL/?oauth=success`로 redirect합니다. 배포에서는 HTTPS, proxy, 외부 callback 주소와 세션 설정을 실제 환경에 맞게 준비해야 합니다.

## 테스트 및 빌드

BE 디렉터리에서 실행합니다.

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
```

테스트 설정은 H2 PostgreSQL 모드와 테스트용 OAuth 등록을 사용합니다. 회원·일지 권한, 활동 저장 멱등성, legacy migration, 이미지 검증, OAuth Handler와 날씨 규칙 등을 검사합니다. 외부 날씨 live 테스트는 조건부이며 실제 공급자 로그인과 현장 GPS 인증을 전체 테스트 통과만으로 검증하는 것은 아닙니다. `clean package`는 테스트를 포함하고 `target`에 실행 JAR를 생성합니다.
