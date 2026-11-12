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

산행 저장은 GPS 구간을 검증하고 요청 식별자와 저장 결과를 대조합니다. 점수 산정 및 구버전 요청의 상세 기준은 [산행 무결성 문서](docs/hiking-activity-integrity.md)를 참고합니다. 회원 이미지 데이터는 DB에 보관합니다. 로그인한 타 회원에게는 버전이 붙은 공개 프로필 이미지 경로를 제공합니다.

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

상세 요청·응답과 권한 정책은 DOCS Repository의 [ToPeak API 명세서.md](https://github.com/csie2026/DOCS/blob/main/ToPeak%20API%20%EB%AA%85%EC%84%B8%EC%84%9C.md)에서 관리합니다.

## 데이터베이스

PostgreSQL을 사용하며 Flyway가 다음 migration을 순서대로 적용합니다.

| 버전 | 내용 |
| --- | --- |
| V1 | 회원, 기존 등산일지 |
| V2 | 산 schema와 seed |
| V3 | 코스 schema와 seed |
| V3.1 | 개인 이미지와 월 목표 테이블 |
| V4 | 실제 활동과 일지 연결 |
| V5 | 산행 점수 원장 |
| V6 | 월 목표 개별 선택 |
| V7 | GPS 활동 검증·요청 지문 |

V4의 일지 연결 FK는 legacy 데이터 보존을 위해 nullable이며 기존 일지는 삭제하거나 활동에 임의 연결하지 않습니다. 새 일지는 필수 활동 ID와 소유권 검증, UNIQUE 제약을 사용합니다. 일지 삭제는 활동을 보존하므로 같은 활동으로 재작성할 수 있습니다. 월 목표는 월별 회원 API로 조회·수정합니다.

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
| FRONTEND_URL | 로그인 성공 후 FE origin. 개발 기본 `http://localhost:5173`, prod 필수 |

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

## 운영 인계 및 심사 시연

1. 담당자가 사용할 HTTPS origin, OAuth 앱 2개, 지도 등록 도메인, 기상청 키, DB 대상·백업과 시연 계정을 확정합니다. FE 빌드 시 `VITE_KAKAO_MAP_JAVASCRIPT_KEY`만 주입합니다. FE 번들에서 읽히므로 Kakao 콘솔에 허용 도메인을 제한합니다. BE 비밀은 런타임 환경변수로 주입합니다. 위 표의 OAuth 변수 네 개와 `FRONTEND_URL`, 운영 DB 변수 세 개, 날씨 시연에 필요한 `KMA_SERVICE_KEY`를 확인합니다.
2. 시연 규모에서는 동일 origin의 TLS 종단·정적 FE와 단일 BE, 비공개 PostgreSQL 구성이 가능합니다. DATA 원천 수집 스크립트의 DATA_GO_KR_KEY는 서비스 기동에 필요하지 않습니다. 단일 인스턴스는 재시작 시 세션 재로그인이 필요합니다. 비용 계획에는 DB 백업·이미지 BYTEA 용량, 저장소·트래픽, 외부 API 할당량을 포함합니다. 도메인·예산·관리 주체가 정해져야 배포할 수 있습니다.
3. DB 담당자가 승인된 대상, 백업 복원 가능 여부, Flyway 이력·체크섬·적용 대기 버전을 먼저 확인합니다. 신규 승인된 빈 DB는 BE 첫 기동 시 V1 → V2 → V3 → V3.1 → V4 → V5 → V6 → V7 순으로 적용되고 JPA가 검증합니다. 기존 DB에는 미적용 버전만 적용합니다. 불일치나 실패가 있으면 기동을 중단하고 이력을 대조합니다. clean·repair·seed 재적용·기존 SQL 수정·운영 DB 초기화는 수행하지 않습니다. 산/코스는 V2/V3에 포함되어 있습니다.
4. FE에서 `npm ci`, `npm run build` 후 `dist/`를 정적 루트로 제공합니다. BE에서 Java 21로 `./mvnw package`(Windows `.\mvnw.cmd package`) 후 `java -jar target/ggmount-0.0.1-SNAPSHOT.jar`로 기동합니다. 환경변수는 기동 전에 주입합니다. `SPRING_PROFILES_ACTIVE=prod`와 스킴·호스트·필요 시 포트로만 구성된 HTTPS `FRONTEND_URL`을 설정합니다(끝 슬래시 없음). 개인 PC 로컬은 위의 local 절차를 사용합니다.
5. 프록시는 승인된 Host만 수락하고 HTTP를 HTTPS로 돌립니다. /api, /oauth2, /login 하위 경로는 원래 경로·메서드·쿠키·CSRF 헤더를 보존해 BE에 전달합니다. 이 경로에 SPA index.html 대체나 공유 캐시를 적용하지 않습니다. 이미지 업로드는 multipart 요청 6MB, GPS JSON은 앱 본문 한도 2MiB와 프록시 제한을 함께 확인합니다.
6. BE 포트는 신뢰한 프록시만 접근하도록 제한합니다. 외부의 Forwarded 및 X-Forwarded-*를 제거하고 프록시가 검증한 원래 Host, 프로토콜, 포트, 클라이언트 주소로 다시 설정합니다. prod는 Tomcat 네이티브 전달 헤더 처리를 사용합니다. 실제 프록시 주소가 Tomcat 신뢰 범위에 있는지 확인하고 필요한 주소만 허용합니다. TLS 종단에서 생성된 Google/Kakao 로그인 이동 URL의 redirect_uri가 각 콘솔에 등록한 **공개 HTTPS origin + /login/oauth2/code/google 또는 /login/oauth2/code/kakao**와 정확히 같은지 확인합니다.
7. 브라우저에서 실제 로그인을 완료하고 JSESSIONID의 Secure·HttpOnly·SameSite=Lax, 동일 origin의 /api/users/me 성공, /api/csrf 발급 및 변경 요청 성공을 확인합니다. Lax는 현재 공급자의 최상위 GET callback에 맞춘 값입니다. 잘못된 토큰의 변경 요청은 403이어야 합니다. 프록시/BE 재시작 후 401이면 같은 계정으로 재로그인하고 저장 대기 기록을 다시 전송합니다.
8. 심사 전에 동의를 받은 전용 OAuth 계정으로 로그인·프로필 설정을 마칩니다. 승인된 시연 DB에서 산·코스 목록을 확인하고, 실제로 미리 저장한 산행과 일지를 준비합니다. 실내 시연은 탐색 → 상세 → 코스 선택 → GPS 권한/정확도 안내 → 마이페이지의 준비된 기록 순으로 진행합니다. 실내에서 정상 도달·거리 인증을 약속하거나 운영 데이터에 가짜 GPS를 넣지 않습니다.

| 증상 | 시연 담당자의 조치 |
| --- | --- |
| OAuth 오류 또는 로그인 반복 | 공급자 상태, 앱 활성화, callback, 공개 origin과 쿠키를 확인합니다. 승인된 기존 세션이 유효하면 그 범위에서 진행하고, 없으면 사전 녹화 자료를 표시합니다. |
| 날씨 502/503, 지도 로딩 실패 | 날씨는 BE 키·사용 승인·할당량을, 지도는 FE 빌드 키·허용 도메인을 확인합니다. 화면의 다시 시도 또는 산 목록·기록 시연으로 진행합니다. |
| API 502/503/504 또는 이미지 413 | BE 기동 상태와 프록시 경로·요청 크기를 확인합니다. 내 이미지 PUT/GET/DELETE의 /api/users/me/images/{profile 또는 background} 경로와 권한을 확인합니다. |
| 저장 중 단절·서버 재시작 | 브라우저 데이터를 유지하고 같은 회원으로 재진입합니다. 같은 종료 기록을 재전송하고 내 기록 수를 확인합니다. |
| 로그 점검 | 프록시 access log는 query를 제외한 경로·상태·시간만 수집합니다. OAuth code/state, KMA serviceKey가 있는 URI, Cookie/Set-Cookie, CSRF, 요청 본문·GPS·이미지, SQL 바인딩, 회원정보와 IP 보존 정책을 점검합니다. 기본 INFO로 운용하며 HTTP/보안 TRACE를 활성화하지 않습니다. 실제 로그 원문을 보고서에 붙이지 않습니다. |

시연 데이터 정리·DB 변경·비용 발생은 팀장과 담당자가 대상과 범위를 승인한 뒤 진행합니다.
