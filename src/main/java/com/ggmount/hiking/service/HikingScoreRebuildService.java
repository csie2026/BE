package com.ggmount.hiking.service;

import com.ggmount.hiking.dto.ScoreSummaryResponse;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HikingScoreRebuildService {
    private final MemberRepository members;
    private final HikingScoreService scores;
    public HikingScoreRebuildService(MemberRepository members, HikingScoreService scores) {
        this.members = members; this.scores = scores;
    }
    @Transactional
    public ScoreSummaryResponse rebuild(long memberId) {
        var member = members.findById(memberId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        scores.lock(member);
        return scores.rebuildLocked(member);
    }
}
