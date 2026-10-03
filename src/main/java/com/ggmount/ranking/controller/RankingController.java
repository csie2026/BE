package com.ggmount.ranking.controller;

import com.ggmount.member.dto.PublicMemberResponse;
import com.ggmount.ranking.service.RankingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

// 로그인한 사용자가 열람하는 랭킹이며, 반환되는 회원 정보는 공개 프로필 범위로 제한한다.
@RestController
@RequestMapping("/api/rankings")
public class RankingController {
    private final RankingService service;

    public RankingController(RankingService service) {
        this.service = service;
    }

    @GetMapping
    public List<PublicMemberResponse> ranking() {
        return service.ranking();
    }
}
