package com.ggmount.member.repository;

import com.ggmount.member.service.ImageUploadValidator.ImageData;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PersonalDataRepository {
    private final JdbcTemplate jdbc;

    public PersonalDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Called inside the service's write transaction. One member's inserts/updates
    // are serialized, including simultaneous first saves from different tabs.
    public void lockMember(Long memberId) {
        jdbc.queryForObject("SELECT id FROM members WHERE id = ? FOR UPDATE", Long.class, memberId);
    }

    // Metadata only: member/profile/ranking reads never load image payloads.
    public Map<String, String> imageRevisions(Long memberId) {
        Map<String, String> revisions = new HashMap<>();
        jdbc.query(
            "SELECT kind, revision FROM member_personal_images WHERE member_id = ?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
            revisions.put(rs.getString("kind"), rs.getString("revision")),
            memberId
        );
        return revisions;
    }

    public Optional<ImageData> findImage(Long memberId, String kind, String revision) {
        String sql = "SELECT content, media_type FROM member_personal_images WHERE member_id = ? AND kind = ?";
        Object[] arguments = {
            memberId, kind
        }
        ;
        if (revision != null) {
            sql += " AND revision = ?";
            arguments = new Object[] {
                memberId, kind, revision
            }
            ;
        }
        // Match owner and revision in one query. A stale URL from another account
        // or an earlier upload must never resolve to the current account's image.
        return jdbc.query(
            sql,
            (rs, row) -> new ImageData(rs.getBytes("content"), rs.getString("media_type")),
            arguments
        ).stream().findFirst();
    }

    // 매 저장마다 새 revision을 발급해 교체 전 URL이 새 이미지의 식별자로 재사용되지 않게 한다.
    public void saveImage(Long memberId, String kind, ImageData image) {
        String revision = UUID.randomUUID().toString();
        int changed = jdbc.update(
            """
                UPDATE member_personal_images SET content = ?, media_type = ?, revision = ?
                WHERE member_id = ? AND kind = ?
                """,
            image.content(),
            image.mediaType(),
            revision,
            memberId,
            kind
        );
        if (changed == 0) {
            jdbc.update(
                """
                    INSERT INTO member_personal_images (member_id, kind, content, media_type, revision)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                memberId,
                kind,
                image.content(),
                image.mediaType(),
                revision
            );
        }
    }

    public void deleteImage(Long memberId, String kind) {
        jdbc.update(
            "DELETE FROM member_personal_images WHERE member_id = ? AND kind = ?",
            memberId,
            kind
        );
    }
}
