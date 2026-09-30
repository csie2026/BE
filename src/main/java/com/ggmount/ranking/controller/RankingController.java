package com.ggmount.ranking.controller;

import com.ggmount.member.dto.PublicMemberResponse;
import com.ggmount.ranking.service.RankingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

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
