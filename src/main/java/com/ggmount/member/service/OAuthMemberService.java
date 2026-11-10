package com.ggmount.member.service;
import com.ggmount.global.auth.oauth.OAuthUserInfo;
import com.ggmount.member.domain.Member;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataIntegrityViolationException;
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
    public Member processLogin(OAuthUserInfo info) {
        boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        boolean[] creating = { false };
        try {
            return transaction.execute(status -> {
                Member member = repository.lockForLogin(info.provider(), info.providerId()).orElseGet(() -> {
                    creating[0] = true;
                    return new Member(info);
                });
                member.refreshOAuth(info);
                return repository.saveAndFlush(member);
            });
        } catch (DataIntegrityViolationException conflict) {
            // Never query inside an aborted transaction. OAuth calls have no enclosing transaction;
            // other callers with one must let their own transaction roll back.
            if (outerTransaction || !creating[0] || !uniqueViolation(conflict)) throw conflict;
            return transaction.execute(status -> {
                Member member = repository.lockForLogin(info.provider(), info.providerId()).orElseThrow(() -> conflict);
                member.refreshOAuth(info);
                return repository.saveAndFlush(member);
            });
        }
    }
    private static boolean uniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
        return false;
    }
}
