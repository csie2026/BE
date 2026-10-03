package com.ggmount.ranking.service;

import com.ggmount.member.dto.PublicMemberResponse;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class RankingService {
    private final MemberRepository repository;

    public RankingService(MemberRepository repository) {
        this.repository = repository;
    }

    // Repository가 정렬한 최대 100명을 공개 DTO로 변환해 본인 전용 회원 정보가 랭킹에 포함되지 않게 한다.
    public List<PublicMemberResponse> ranking() {
        return repository.findRanking(PageRequest.of(0, 100)).stream()
            .map(PublicMemberResponse::from).toList();
    }
}
