# BE 초기 패키지 구조

## 기존 설정

- 기본 패키지: `com.ggmount`
- Java 21, Maven, Spring Boot 4.1.1
- 기존 의존성: Spring Data JPA, Validation, Web MVC, Lombok 및 관련 테스트 스타터
- `pom.xml`, 애플리케이션 진입점, 기존 `application.yaml`은 유지합니다.
- Spring Security, OAuth2 Client 의존성을 추가했습니다. JWT 자체 구현, JDBC 드라이버, DB 접속 설정은 없습니다.

## 파일을 생성한 이유

아래 목록 중 SecurityConfig는 OAuth 보안 설정으로 구현했습니다. 나머지 기존 Java 파일은 초기 구조용 빈 선언입니다.

| 파일 (src/main/java/com/ggmount 기준) | 생성 이유 |
| --- | --- |
| global/config/SecurityConfig.java | SecurityFilterChain, OAuth 로그인, 공개 URL 및 인증 필요 URL 설정 |
| global/config/WebConfig.java | 향후 CORS 등 공통 웹 설정 위치 확보 |
| global/exception/GlobalExceptionHandler.java | 예외 응답 규격 확정 후 공통 예외 처리 구현 위치 확보 |
| global/exception/BusinessException.java | 업무 예외 계약 구현 위치 확보. 현재는 RuntimeException을 상속하지 않는 자리표시자 |
| global/exception/ErrorCode.java | 오류 코드 규격을 모을 빈 enum 확보 |
| member/controller/MemberController.java | 회원 API 진입점 위치 확보 |
| member/service/MemberService.java | 회원 업무 로직 위치 확보 |
| member/repository/MemberRepository.java | 회원 저장소 인터페이스 위치 확보. 엔티티·ID 미확정으로 JpaRepository 상속 보류 |
| member/domain/Member.java | 회원 모델 위치 확보. 필드·관계·JPA 매핑은 미정 |
| member/dto/MemberResponse.java | 회원 응답 계약 위치 확보 |
| member/dto/MemberUpdateRequest.java | 회원 수정 요청 계약 위치 확보 |
| hiking/domain/HikingRecord.java | 등산 기록 모델 위치 확보. 필드·관계·JPA 매핑은 미정 |
| src/main/resources/application-local.yml (BE 기준) | local 프로필에서 BE/.env를 선택적으로 가져오는 설정 |
| src/main/resources/application-prod.yml (BE 기준) | 운영 환경별 설정 위치 확보. 현재 주석만 포함 |
| 각 패키지의 .gitkeep | 빈 패키지를 포함한 디렉터리를 Git으로 추적할 수 있도록 유지 |
| README.md (이 파일) | 구조, 생성 이유 및 미구현 범위 기록 |

## 패키지 역할

- `global/config`: 공통 설정
- `global/auth/oauth`: Google/Kakao 연동, 사용자 정보 정규화 및 로그인 결과 처리
- `global/auth/jwt`: JWT 채택 시 토큰 발급·검증 관련 구현
- `global/exception`: 공통 예외 체계
- `global/common`: 여러 기능에서 실제로 공유하게 되는 코드
- `member`, `mountain`, `hiking`, `ranking`: 각각 `controller`, `service`, `repository`, `domain`, `dto`로 구성

`com.ggmount`와 모든 하위 패키지에 코드 유무와 관계없이 `.gitkeep`을 추가했습니다. mountain·ranking 및 아직 구현할 클래스가 정해지지 않은 계층은 `.gitkeep`만 포함합니다.

## 인증 및 후속 구현

Google/Kakao 로그인은 Spring Security OAuth2 Client로 처리합니다. 현재는 기본 HTTP 세션으로 인증을 유지하며 CSRF 보호도 유지합니다. 로그인 관련 URL(`/oauth2/authorization/**`, `/login/**`)과 `/error`는 공개하고 나머지 요청은 인증이 필요합니다.

로그인 시작 URL:

- Google: `GET /oauth2/authorization/google`
- Kakao: `GET /oauth2/authorization/kakao`

성공하면 HTTP 200과 `{"status":"success"}`, 실패하면 HTTP 401과 `{"error":"oauth_login_failed"}`를 반환합니다. 성공은 공급자 인증 완료를 의미하며, 아직 DB 회원 생성이나 JWT 발급은 수행하지 않습니다.

### OAuth 파일 역할

Java 경로는 `src/main/java/com/ggmount` 기준입니다.

| 생성 파일 | 역할 |
| --- | --- |
| global/auth/oauth/OAuthUserInfo.java | provider, providerId, email, nickname, profileImage 공통 객체 |
| global/auth/oauth/OAuthUserInfoMapper.java | Google의 sub 및 Kakao의 id/kakao_account.profile 응답 변환 |
| global/auth/oauth/OAuthPrincipal.java | 공통 정보와 원본 속성을 보관하며 provider:providerId로 계정을 구별 |
| global/auth/oauth/CustomOAuth2UserService.java | 공급자 사용자 정보 조회 → 공통 정보 변환 → 회원 서비스 연결 |
| global/auth/oauth/OAuth2LoginSuccessHandler.java | 성공 응답, 임시 인증 오류 및 저장 요청 정리, JWT 발급 TODO |
| global/auth/oauth/OAuth2LoginFailureHandler.java | 민감한 공급자 오류를 노출하지 않는 실패 응답 |
| member/service/OAuthMemberService.java | DB 연동 지점: 기존 회원 조회, 신규 생성, 기존 회원 반환 TODO |
| src/test/java/com/ggmount/global/auth/oauth/OAuthUserInfoMapperTests.java (BE 기준) | 두 공급자 응답 및 선택 정보 누락, 잘못된 식별자 검증 |
| src/test/java/com/ggmount/global/auth/oauth/OAuth2LoginHandlerTests.java (BE 기준) | 성공/실패 응답 및 인증 임시 상태 정리 검증 |

수정 파일은 `pom.xml`(Security/OAuth2 Client 의존성), `application.yaml`(환경변수 및 공급자 설정), `SecurityConfig.java`(보안 필터), `README.md`(설정 안내)입니다. 기존 `application.yaml`을 사용하며 별도 `application.yml`은 만들지 않았습니다.

### 키 발급 후 설정

로컬에서는 BE/.env에, 운영에서는 서버/배포 플랫폼의 환경변수에 다음 값을 등록합니다. 실제 값이나 비밀키 파일은 저장소에 추가하지 않습니다.

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `KAKAO_CLIENT_ID` — Kakao REST API 키
- `KAKAO_CLIENT_SECRET` — 해당 REST API 키의 Client Secret

### 로컬 .env 준비

1. clone 후 BE 디렉터리에서 `Copy-Item .env.example .env` (PowerShell) 또는 `cp .env.example .env` (macOS/Linux)를 실행합니다. 이미 .env가 있으면 덮어쓰지 마세요.
2. 발급받은 실제 키를 .env의 각 등호 뒤에 입력합니다. 키 발급 전에는 빈 상태로 두며 가짜 값을 넣지 않습니다.
3. IntelliJ 실행 설정의 Working directory를 BE 디렉터리로 지정하고, Active profiles를 `local`로 설정합니다. Active profiles 항목이 없으면 Program arguments에 `--spring.profiles.active=local`을 지정합니다. OAuth 키를 IntelliJ 환경변수에 중복 입력할 필요는 없습니다.
4. 터미널 실행은 BE 디렉터리에서 `./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"`을 사용합니다. Windows에서는 `./mvnw.cmd`를 사용합니다. 실제 애플리케이션 실행에는 아래의 기존 DB 설정 제약도 적용됩니다.

Spring Boot 4.1.1의 기본 Config Data 기능을 사용하며 라이브러리는 추가하지 않습니다. local 프로필의 `spring.config.import: "optional:file:./.env[.properties]"`가 .env를 Java properties 형식으로 읽어 Spring Environment에 등록합니다. 따라서 기존 application.yaml의 `${GOOGLE_CLIENT_ID}` 등에서 그대로 참조할 수 있습니다. OS 환경변수 자체를 생성하는 방식은 아닙니다.

- `.env`: 개인 로컬 값. .gitignore에 의해 Git에서 제외되며 BE 실행 작업 디렉터리 기준으로 읽습니다.
- `.env.example`: 네 변수 이름과 빈 값만 담은 Git 추적용 템플릿. 자동으로 읽지는 않습니다.
- `application.yaml`: 모든 환경에 공통인 OAuth 설정과 `${...}` 참조를 유지합니다.
- `application-local.yml`: local 프로필에서만 .env를 가져옵니다.

.env는 `KEY=VALUE` 형식으로 작성합니다. 일반 dotenv 파서가 아니라 properties 파서를 사용하므로 값을 따옴표로 감싸거나 `export`를 붙이지 마세요. 주석은 별도 줄에서 `#`으로 시작하며, 값 뒤에 인라인 주석을 붙이지 마세요.

`optional:` 덕분에 .env 파일이 없어도 파일 누락 자체로 빌드/테스트가 실패하지 않습니다. 다만 실제 OAuth 로그인에는 유효한 키가 필요하며, 기존 전체 컨텍스트 테스트의 DB 설정 요구사항을 없애는 설정은 아닙니다. 빈 .env를 복사한 것만으로 서버 실행 준비가 완료되지는 않습니다.

### 운영 환경 주입

운영에서는 `SPRING_PROFILES_ACTIVE=prod`를 설정하고 local 프로필을 함께 활성화하지 않습니다. 서버/컨테이너/배포 플랫폼의 환경변수 또는 Secret 관리 기능을 통해 위 네 OAuth 값을 실행 프로세스에 주입합니다. .env 파일은 서버에 배포하지 않습니다. prod 프로필은 로컬 .env 가져오기 설정을 활성화하지 않으며 기존 `${...}` 참조가 환경변수에서 값을 읽습니다. 같은 이름의 OS 환경변수가 있으면 로컬 파일 값보다 우선합니다.

참고: [Spring Boot 외부 설정 — Config Data 가져오기, 확장자 힌트 및 우선순위](https://docs.spring.io/spring-boot/reference/features/external-config.html).

Google Cloud Console에서 웹 애플리케이션 OAuth 클라이언트를 만들고, 승인된 리디렉션 URI에 `http://localhost:8080/login/oauth2/code/google`을 등록합니다. 요청 scope는 `profile`, `email`만 사용하며 Google 기본 Provider를 활용합니다.

Kakao Developers에서 카카오 로그인을 활성화하고 Redirect URI에 `http://localhost:8080/login/oauth2/code/kakao`를 등록합니다. REST API 키와 Client Secret을 사용하며 토큰 요청 인증은 `client_secret_post`입니다. 현재 추가 scope를 지정하지 않아 앱의 기본 동의항목을 사용합니다. 필요한 경우 Developers에서 동의항목을 설정한 뒤 `application.yaml`의 주석 예시를 참고해 `profile_nickname`, `profile_image`, `account_email` 중 필요한 항목만 추가하세요. 프로필/이메일이 없으면 공통 객체의 해당 값은 null입니다.

운영에서는 두 URI의 `http://localhost:8080`을 실제 백엔드 HTTPS 주소로 바꾸어 각 콘솔에 등록합니다. 설정의 `{baseUrl}/login/oauth2/code/{registrationId}`와 정확히 일치해야 합니다. 프록시 사용 시 서버가 외부 주소를 인식하도록 배포 환경에서 신뢰할 프록시의 전달 헤더 설정도 확인하세요.

공식 문서: [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html), [Kakao 로그인 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api).

### 남겨둔 TODO

- `OAuthMemberService.processLogin`: provider + providerId로 회원 조회, 없으면 생성, 있으면 반환. 회원 모델 확정 후 반환 타입과 principal 연결 구현
- `OAuth2LoginSuccessHandler`: JWT Access Token / Refresh Token 발급, 프론트엔드 토큰 전달 및 완료 응답 방식
- DBMS, 드라이버, 접속 설정 및 회원 Entity/Repository 확정 (기존 빈 선언 유지)

미확정 회원 필드, 등산 기록 항목, 산 검색 API, 순위 계산 규칙, AI 기능은 추가하지 않았습니다. 환경 설정에 실제 비밀값을 커밋하지 말고 환경 변수로 주입합니다. 프로필은 향후 `SPRING_PROFILES_ACTIVE=local` 또는 `prod`로 선택하며 기본 활성 프로필은 강제하지 않습니다.

## 검증 및 Git

- 컴파일 확인: `mvn -DskipTests compile` 또는 `./mvnw -DskipTests compile`
- OAuth 단위 테스트: `mvn "-Dtest=OAuthUserInfoMapperTests,OAuth2LoginHandlerTests" test` (DB, 실제 키, 공급자 네트워크 요청 불필요)
- 기존 `contextLoads` 테스트 및 애플리케이션 실행에는 OAuth 환경변수와 JPA용 DB 드라이버·접속 설정이 필요합니다. OAuth 키만으로 기존 미완성 DB 설정까지 해결되지는 않습니다.
- 실제 공급자 로그인은 키 발급, 콘솔 설정 및 DB 실행 환경 구성 후 브라우저로 검증해야 합니다.
- `.gitkeep`은 Git의 특별한 기능이 아닌 일반 추적 파일입니다. 커밋에 포함하면 빈 패키지 구조도 원격에 보존됩니다.
- 이번 작업은 구조 파일 생성까지이며 커밋과 원격 push는 수행하지 않습니다.

## 프로필·등산일지·랭킹 구현 업데이트

위의 초기 구조/TODO 설명은 구현 전 기록입니다. 현재 회원과 일지를 JPA에 저장하고 Flyway V1 migration으로 스키마를 관리합니다. API와 변경 사항은 루트의 IMPLEMENTATION_REPORT.md를 참고하세요.

- 기존 HTTP 세션 인증과 CSRF 보호를 유지합니다. FE는 GET /api/csrf에서 받은 토큰을 쓰기 요청에 전달합니다.
- OAuth 성공 응답은 JSON에서 FRONTEND_URL/?oauth=success 리디렉션으로 변경했습니다. FRONTEND_URL 기본값은 http://localhost:5173입니다.
- PostgreSQL 접속은 local 프로필의 DB_URL, DB_USERNAME, DB_PASSWORD 설정을 사용합니다. 운영에서는 SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD와 OAuth 환경변수를 주입합니다.
- Flyway V1은 저장소에 기존 Entity/migration이 없던 상태를 기준으로 작성했습니다. 기존 운영 DB의 테이블은 직접 확인하지 않았습니다. 이미 비어 있지 않은 스키마는 적용 전에 스키마와 Flyway baseline 정책을 검토해야 합니다. 자동 baseline이나 기존 테이블 삭제는 하지 않습니다.
- 테스트: mvn test. 실행 JAR: mvn package. 테스트는 H2 PostgreSQL 모드와 테스트용 OAuth 설정으로 외부 키 없이 실행됩니다.
- 개발 FE의 Vite proxy 또는 운영 reverse proxy에서 /api, /oauth2, /login을 BE에 연결해 같은 origin의 세션을 사용합니다.
- 점수 공식이 미확정이므로 score는 null(미산정)이며 일지 작성 시 임의 점수를 부여하지 않습니다.
