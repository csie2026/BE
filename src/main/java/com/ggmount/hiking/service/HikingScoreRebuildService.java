package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.ScoreSummaryResponse;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 지정 회원의 재구축을 위한 트랜잭션 진입점이며, 계산·저장은 일반 재산정과 같은 HikingScoreService에 위임한다.
 * 프로필 완료 여부나 기존 정책 버전으로 대상을 거르지 않으며, 해당 회원이 없으면 404 상태 예외를 발생시킨다.
 */
@Service
public class HikingScoreRebuildService {
    private final MemberRepository members;
    private final HikingScoreService scores;
    public HikingScoreRebuildService(MemberRepository members, HikingScoreService scores) {
        this.members = members; this.scores = scores;
    }
    // 실행기의 각 호출은 회원 한 명의 트랜잭션으로 처리한다. 외부 트랜잭션이 있으면 기본 전파 방식에 따라 참여한다.
    @Transactional
    public ScoreSummaryResponse rebuild(long memberId) {
        var member = members.findById(memberId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 회원 행을 잠근 뒤 현재 활동과 공개 일지를 다시 읽어 재산정한다. 잠금·저장은 같은 트랜잭션에 속한다.
        scores.lock(member);
        // 기존 버전과 비교해 생략하지 않고 현재 정책으로 결과를 교체한다. 정책 버전 변경만으로 자동 실행되지는 않는다.
        // 같은 자료·정책으로 재실행해도 누적 가산하지 않는다. 오류는 전파하며 런타임 예외 시 해당 트랜잭션을 롤백한다.
        return scores.rebuildLocked(member);
    }
}
