# BE 초기 패키지 구조

## 기존 설정

- 기본 패키지: `com.ggmount`
- Java 21, Maven, Spring Boot 4.1.1
- 기존 의존성: Spring Data JPA, Validation, Web MVC, Lombok 및 관련 테스트 스타터
- `pom.xml`, 애플리케이션 진입점, 기존 `application.yaml`은 유지합니다.
- 현재 Security/OAuth2/JWT 의존성, JDBC 드라이버, DB 접속 설정은 없습니다.

## 파일을 생성한 이유

아래 Java 파일은 초기 구조용 빈 선언입니다. Spring 빈, API, DB 엔티티 또는 실제 인증 기능으로 등록하지 않았습니다.

| 파일 (src/main/java/com/ggmount 기준) | 생성 이유 |
| --- | --- |
| global/config/SecurityConfig.java | 향후 인증·인가 및 보안 필터 설정 위치 확보 |
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
- `global/auth/oauth`: 향후 소셜 로그인 공급자 연동 및 인증 결과 처리
- `global/auth/jwt`: JWT 채택 시 토큰 발급·검증 관련 구현
- `global/exception`: 공통 예외 체계
- `global/common`: 여러 기능에서 실제로 공유하게 되는 코드
- `member`, `mountain`, `hiking`, `ranking`: 각각 `controller`, `service`, `repository`, `domain`, `dto`로 구성

`com.ggmount`와 모든 하위 패키지에 코드 유무와 관계없이 `.gitkeep`을 추가했습니다. mountain·ranking 및 아직 구현할 클래스가 정해지지 않은 계층은 `.gitkeep`만 포함합니다.

## 인증 및 후속 구현

소셜 공급자, 사용자 식별 방식, 회원 연결 정책, 세션/JWT 사용 여부, 토큰 전달·저장 방식이 확정되면 필요한 의존성과 구현을 추가합니다. 현재 SecurityConfig는 빈 클래스이므로 API 접근을 보호하지 않습니다. OAuth 콜백, 토큰 발급 및 로그인 API도 아직 없습니다.

미확정 회원 필드, 등산 기록 항목, 산 검색 API, 순위 계산 규칙, AI 기능은 추가하지 않았습니다. 환경 설정에 실제 비밀값을 커밋하지 말고 환경 변수로 주입합니다. 프로필은 향후 `SPRING_PROFILES_ACTIVE=local` 또는 `prod`로 선택하며 기본 활성 프로필은 강제하지 않습니다.

## 검증 및 Git

- 컴파일 확인: `mvn -DskipTests compile` 또는 `./mvnw -DskipTests compile`
- 기존 `contextLoads` 테스트 및 애플리케이션 실행에는 JPA용 DB 드라이버·접속 설정이 필요합니다. 구조 작업에서 DB 제품을 임의로 선택하지 않았습니다.
- `.gitkeep`은 Git의 특별한 기능이 아닌 일반 추적 파일입니다. 커밋에 포함하면 빈 패키지 구조도 원격에 보존됩니다.
- 이번 작업은 구조 파일 생성까지이며 커밋과 원격 push는 수행하지 않습니다.
