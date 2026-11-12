package com.ggmount.member.repository;

import com.ggmount.member.service.ImageUploadValidator.ImageData;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 이미지 바이트와 미디어 유형·revision을 데이터베이스에 저장하며 파일시스템 경로는 사용하지 않는다. */
@Repository
public class PersonalDataRepository {
    private final JdbcTemplate jdbc;

    public PersonalDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 서비스의 쓰기 트랜잭션에서 회원 행을 잠가 같은 회원의 이미지 저장·삭제를 순차 처리한다.
    // 이미지 행이 없는 최초 저장도 기존 회원 행에 대한 잠금으로 조정한다.
    public void lockMember(Long memberId) {
        jdbc.queryForObject("SELECT id FROM members WHERE id = ? FOR UPDATE", Long.class, memberId);
    }

    // 본인 회원 응답에 필요한 이미지 종류별 revision만 조회하며 이미지 바이트는 읽지 않는다.
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
        // 내부 회원 ID와 이미지 종류를 함께 대조하고, revision이 전달된 경우 현재 값과의 일치도 요구한다.
        // 일치하는 행이 없으면 빈 결과를 반환하며 데이터 접근 예외는 서비스 호출부로 전파한다.
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

    // 해당 회원·종류의 이미지 행만 삭제하며, 삭제할 행이 없어도 별도 예외를 발생시키지 않는다.
    public void deleteImage(Long memberId, String kind) {
        jdbc.update(
            "DELETE FROM member_personal_images WHERE member_id = ? AND kind = ?",
            memberId,
            kind
        );
    }

    // 공개 프로필·랭킹 응답에 필요한 프로필 이미지 revision을 회원 ID 목록으로 한 번에 조회한다.
    // 이미지 바이트는 읽지 않으며 회원 ID 목록이 비어 있으면 쿼리를 실행하지 않는다.
    public Map<Long, String> profileRevisions(java.util.Collection<Long> memberIds) {
        Map<Long, String> revisions = new HashMap<>();
        if (memberIds.isEmpty()) return revisions;
        String placeholders = String.join(",", java.util.Collections.nCopies(memberIds.size(), "?"));
        jdbc.query("SELECT member_id, revision FROM member_personal_images WHERE kind='profile' AND member_id IN (" + placeholders + ")",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> revisions.put(rs.getLong(1), rs.getString(2)), memberIds.toArray());
        return revisions;
    }
}
