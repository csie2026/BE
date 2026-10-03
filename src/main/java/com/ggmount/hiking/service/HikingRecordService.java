package com.ggmount.hiking.service;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.domain.HikingRecord;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.repository.HikingRecordRepository;
import com.ggmount.member.service.MemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
@Service
@Transactional(readOnly = true)
public class HikingRecordService {
    private final HikingRecordRepository repository;
    private final MemberService members;
    public HikingRecordService(HikingRecordRepository repository, MemberService members) {
        this.repository = repository;
        this.members = members;
    }
    public List<HikingRecordResponse> mine(OAuthPrincipal p) {
        return repository.findByMemberIdOrderByHikingDateDescIdDesc(members.current(p).getId())
            .stream()
            .map(HikingRecordResponse::from)
            .toList();
    }
    // 비공개 일지는 작성자만 볼 수 있으며, 타인 접근에는 404를 반환해 일지 존재 여부도 숨긴다.
    public HikingRecordResponse detail(OAuthPrincipal p, Long id) {
        var member = members.current(p);
        var record = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!record.isPublicRecord() && !record.getMember().getId().equals(member.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return HikingRecordResponse.from(record);
    }
    // 전체 피드와 타인 프로필 목록에는 공개 일지만 포함하고, 본인 전체 목록은 별도 조회를 사용한다.
    public List<HikingRecordResponse> publicRecords(Long userId) {
        if (userId == null)
            return repository.findTop100ByPublicRecordTrueOrderByHikingDateDescIdDesc()
                .stream()
                .map(HikingRecordResponse::from)
                .toList();
        members.requireComplete(members.find(userId));
        return repository.findByMemberIdAndPublicRecordTrueOrderByHikingDateDescIdDesc(userId)
            .stream()
            .map(HikingRecordResponse::from)
            .toList();
    }
    @Transactional
    // 신규 작성은 현재 회원에 연결하고, 수정은 일지 ID와 현재 회원 ID를 함께 조회해 소유권을 검증한다.
    public HikingRecordResponse save(OAuthPrincipal p, Long id, HikingRecordRequest request) {
        var member = members.current(p);
        members.requireComplete(member);
        HikingRecord record = id == null
            ? new HikingRecord(member, request)
            : repository.findByIdAndMemberId(id, member.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        record.update(request);
        return HikingRecordResponse.from(repository.save(record));
    }
    @Transactional
    // 삭제도 일지와 회원 ID를 함께 확인하므로 FE의 버튼 표시 여부와 무관하게 작성자만 삭제할 수 있다.
    public void delete(OAuthPrincipal p, Long id) {
        var member = members.current(p);
        members.requireComplete(member);
        var record = repository.findByIdAndMemberId(id, member.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repository.delete(record);
    }
}
