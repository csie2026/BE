package com.ggmount.ranking.service;

import com.ggmount.member.dto.PublicMemberResponse;
import com.ggmount.member.repository.MemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import com.ggmount.member.repository.PersonalDataRepository;
import com.ggmount.member.domain.Member;
import com.ggmount.member.dto.ProfileImageUrls;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ggmount.member.service.MemberService;
import com.ggmount.ranking.dto.RankingSummary;
import com.ggmount.ranking.dto.RankingEntry;
import com.ggmount.global.auth.oauth.OAuthPrincipal;

/**
 * 점수 서비스가 회원에 저장한 점수를 조회하며 점수 계산·재산정·저장은 수행하지 않는다.
 * 두 조회 모두 프로필 완료 회원만 대상으로 하며, 탈퇴로 삭제된 회원 행은 조회 대상에 없다.
 * 재산정한 점수는 회원에 저장·커밋된 뒤 조회 트랜잭션에서 보이는 값으로 반영된다.
 */
@Service
@Transactional(readOnly = true)
public class RankingService {
    private final MemberRepository repository;
    private final JdbcTemplate jdbc;
    private final MemberService members;
    private final PersonalDataRepository images;

    public RankingService(MemberRepository repository,
        JdbcTemplate jdbc,
        MemberService members,
        PersonalDataRepository images) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.members = members;
        this.images = images;
    }

    // Repository가 정렬한 최대 100명을 공개 DTO로 변환해 본인 전용 회원 정보가 랭킹에 포함되지 않게 한다.
    // 고정된 첫 페이지 100건을 사용하며 클라이언트의 페이지 입력은 없다. 이미지 버전은 회원 목록으로 일괄 조회한다.
    public List<PublicMemberResponse> ranking() {
        var top = repository.findRanking(PageRequest.of(0, 100));
        var revisions = images.profileRevisions(top.stream().map(Member::getId).toList());
        return top.stream().map(member -> PublicMemberResponse.withImage(member, revisions.get(member.getId()))).toList();
    }

    /**
     * 단일 SQL 문으로 상위 100명·전체 기준 본인 순위·참여자 수를 같은 조회 시점의 결과에서 구성한다.
     * 일반 조회와 같이 점수가 있는 회원을 먼저 두고 점수 내림차순·내부 회원 ID 오름차순으로 정렬한다.
     * null 점수도 마지막 순서의 참여자로 포함하며, ROW_NUMBER로 1부터 순번을 부여해 동점도 공동 순위가 없다.
     */
    public RankingSummary summary(OAuthPrincipal principal) {
        // 세션의 내부 회원 ID와 공급자 정보를 대조해 본인을 확인한다. 프로필 미완료 회원은 본인 순위가 없다.
        long memberId = members.current(principal).getId();
        record Row(RankingEntry entry, long total) {}
        var rows = jdbc.query("""
            WITH ranked AS (
              SELECT id, nickname, profile_image_url, score,
                ROW_NUMBER() OVER (ORDER BY CASE WHEN score IS NULL THEN 1 ELSE 0 END, score DESC, id ASC) AS position,
                COUNT(*) OVER () AS total_members
              FROM members WHERE profile_completed = TRUE
            )
            SELECT r.id,r.nickname,r.profile_image_url,r.score,r.position,r.total_members,
              image.revision AS profile_revision
            FROM ranked r LEFT JOIN member_personal_images image ON image.member_id=r.id AND image.kind='profile'
            WHERE r.position <= 100 OR r.id = ? ORDER BY r.position
            """, (rs, i) -> new Row(new RankingEntry(
                rs.getLong("id"), rs.getString("nickname"), ProfileImageUrls.publicProfile(
                    rs.getLong("id"), rs.getString("profile_revision"), rs.getString("profile_image_url")),
                rs.getObject("score", Long.class), rs.getLong("position")), rs.getLong("total_members")), memberId);
        // 참여자 수·순위는 상위 100명 제한 전에 계산한다. 본인이 100위 밖이어도 별도로 조회하며 목록에는 넣지 않는다.
        var top = rows.stream().map(Row::entry).filter(entry -> entry.rank() <= 100).toList();
        var mine = rows.stream().map(Row::entry).filter(entry -> entry.userId() == memberId).findFirst().orElse(null);
        // 대상 회원이 없으면 전체 수는 0이며, 본인이 랭킹 대상이 아니면 mine은 null이다.
        return new RankingSummary(top, mine, rows.isEmpty() ? 0 : rows.get(0).total());
    }
}
