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
    public List<PublicMemberResponse> ranking() {
        var top = repository.findRanking(PageRequest.of(0, 100));
        var revisions = images.profileRevisions(top.stream().map(Member::getId).toList());
        return top.stream().map(member -> PublicMemberResponse.withImage(member, revisions.get(member.getId()))).toList();
    }

    /** One statement gives top 100, own full-list position and participant count in one snapshot.
     * The order matches the legacy repository: scored members first, score DESC, ID ASC.
     * Null scores remain participants at the end; equal scores do not share a rank.
     */
    public RankingSummary summary(OAuthPrincipal principal) {
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
        var top = rows.stream().map(Row::entry).filter(entry -> entry.rank() <= 100).toList();
        var mine = rows.stream().map(Row::entry).filter(entry -> entry.userId() == memberId).findFirst().orElse(null);
        return new RankingSummary(top, mine, rows.isEmpty() ? 0 : rows.get(0).total());
    }
}
