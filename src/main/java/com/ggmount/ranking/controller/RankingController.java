package com.ggmount.ranking.controller;

import com.ggmount.member.dto.PublicMemberResponse;
import com.ggmount.ranking.service.RankingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import com.ggmount.ranking.dto.RankingSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.ggmount.global.auth.oauth.OAuthPrincipal;

// 로그인한 사용자가 열람하는 랭킹이며, 반환되는 회원 정보는 공개 프로필 범위로 제한한다.
@RestController
@RequestMapping("/api/rankings")
public class RankingController {
    private final RankingService service;

    public RankingController(RankingService service) {
        this.service = service;
    }

    // 별도 요청 파라미터 없이 상위 최대 100명의 공개 회원 정보를 반환하며, 응답은 캐시에 저장하지 않도록 지정한다.
    // 두 API 모두 점수 계산·갱신은 수행하지 않고 저장된 점수를 조회한다.
    @GetMapping
    public ResponseEntity<List<PublicMemberResponse>> ranking() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.ranking());
    }

    // 상위 목록·본인 전체 순위·참여자 수를 반환한다. 본인은 인증 정보로 확인하며 사용자 ID를 입력받지 않는다.
    // 본인 확인 실패 등의 예외는 여기서 처리하지 않고 공통 예외 처리 흐름으로 전파한다.
    @GetMapping("/summary")
    public ResponseEntity<RankingSummary> summary(
        @AuthenticationPrincipal OAuthPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.summary(principal));
    }
}
