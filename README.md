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
| src/main/resources/application-local.yml (BE 기준) | 로컬 환경별 설정 위치 확보. 현재 주석만 포함 |
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

실행 프로세스 또는 IDE 실행 설정에 다음 환경변수를 등록합니다. 실제 값이나 비밀키 파일은 저장소에 추가하지 않습니다.

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `KAKAO_CLIENT_ID` — Kakao REST API 키
- `KAKAO_CLIENT_SECRET` — 해당 REST API 키의 Client Secret

기존 `.env.example`이 없어 새로 생성하지 않았습니다. Spring Boot가 `.env` 파일을 자동으로 읽는 구성은 아니므로 OS/IDE/배포 환경에서 변수를 주입해야 합니다. 환경변수를 미설정한 상태는 실제 OAuth 실행을 지원하지 않습니다.

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
