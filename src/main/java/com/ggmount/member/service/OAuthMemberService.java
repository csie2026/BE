package com.ggmount.member.service;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.domain.Member;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataIntegrityViolationException;
/** 공급자 계정에 대응하는 내부 회원을 생성·갱신하고, 동시 최초 생성의 유일성 충돌을 제한적으로 복구한다. */
@Service
public class OAuthMemberService {
    private final MemberRepository repository;
    private final TransactionTemplate transaction;
    public OAuthMemberService(MemberRepository repository, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactions);
    }
    // 공급자와 외부 사용자 ID의 조합으로 기존 회원을 찾는다.
    // 재로그인은 OAuth 정보만 갱신해 사용자가 완료한 닉네임·출생연도 설정을 덮어쓰지 않는다.
    /**
     * 기본 전파 방식의 트랜잭션 템플릿으로 회원을 저장하므로 외부 트랜잭션이 있으면 참여하고, 없으면 새로 시작한다.
     * 외부 트랜잭션 없이 신규 생성을 시도하다 유일성 충돌이 발생한 경우에만 별도 트랜잭션으로 재조회한다.
     *
     * @throws DataIntegrityViolationException 복구 조건에 해당하지 않거나, 재조회 시 회원이 없거나, 재저장에 실패한 경우
     */
    public Member processLogin(OAuthUserInfo info) {
        boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        boolean[] creating = { false };
        try {
            return transaction.execute(status -> {
                // 기존 행은 쓰기 잠금으로 조회하지만, 아직 행이 없는 동시 최초 로그인은 각각 생성을 시도할 수 있다.
                Member member = repository.lockForLogin(info.provider(), info.providerId()).orElseGet(() -> {
                    creating[0] = true;
                    return new Member(info);
                });
                member.refreshOAuth(info);
                return repository.saveAndFlush(member);
            });
        } catch (DataIntegrityViolationException conflict) {
            // 실패한 트랜잭션에서 조회하지 않는다. 외부 트랜잭션이 없으면 첫 실행의 롤백이 끝난 뒤 이 지점에 도달한다.
            // 외부 트랜잭션에 참여했다면 호출자가 롤백하도록 예외를 전파하며, 생성 시도의 유일성 위반만 복구 대상으로 삼는다.
            if (outerTransaction || !creating[0] || !uniqueViolation(conflict)) throw conflict;
            // 새 트랜잭션에서 같은 공급자 계정을 다시 잠금 조회해 먼저 생성된 회원을 갱신한다.
            // 회원을 찾지 못하면 원래 충돌을 전파하며, 이 재시도의 실패를 다시 반복 처리하지 않는다.
            return transaction.execute(status -> {
                Member member = repository.lockForLogin(info.provider(), info.providerId()).orElseThrow(() -> conflict);
                member.refreshOAuth(info);
                return repository.saveAndFlush(member);
            });
        }
    }
    // 원인 예외의 SQL 상태가 23505인 경우만 유일성 위반으로 판별하며, 다른 무결성 오류는 재시도하지 않는다.
    private static boolean uniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
        return false;
    }
}
