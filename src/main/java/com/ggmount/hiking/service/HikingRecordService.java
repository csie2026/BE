package com.ggmount.hiking.service;
import com.ggmount.global.auth.oauth.OAuthPrincipal;
import com.ggmount.hiking.domain.HikingRecord;
import com.ggmount.hiking.dto.*;
import com.ggmount.hiking.repository.HikingRecordRepository;
import com.ggmount.hiking.repository.HikingActivityRepository;
import com.ggmount.member.service.MemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
// 일지 쓰기는 회원 잠금 아래 수행하고, 일지 변경과 점수 재산정을 같은 트랜잭션으로 처리한다.
// 활동 연결이 없는 기존 일지도 조회·수정·삭제 대상이며, 이 서비스에서 산행 활동 자체를 수정·삭제하지 않는다.
@Service
@Transactional(readOnly = true)
public class HikingRecordService {
    private final HikingRecordRepository repository;
    private final MemberService members;
    private final HikingActivityRepository activities;
    private final HikingScoreService scores;
    public HikingRecordService(HikingRecordRepository repository, MemberService members, HikingActivityRepository activities, HikingScoreService scores) {
        this.repository = repository;
        this.members = members;
        this.activities = activities;
        this.scores = scores;
    }
    // 세션의 내부 회원 ID와 공급자 정보를 대조해 본인을 확인하고 공개 여부와 무관하게 본인 일지를 조회한다.
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
    // 전체 피드는 최대 100건이며, 회원 지정 조회는 대상 회원이 없으면 404, 프로필 미완료이면 403이다.
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
    // 생성·수정·삭제는 프로필 완료 회원만 가능하며, 런타임 예외가 발생하면 일지와 점수 변경을 함께 롤백한다.
    public HikingRecordResponse create(OAuthPrincipal p, HikingRecordRequest request) {
        var member = members.current(p);
        members.requireComplete(member);
        scores.lock(member);
        // 회원 행 다음에 본인 산행 활동을 잠근다. 산행이 없거나 타인 소유이면 404, 이미 일지가 연결되었으면 409다.
        var activity = activities.lockOwned(request.hikingRecordId(), member.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "등산기록을 찾을 수 없습니다."));
        if (repository.existsByActivityId(activity.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등산일지를 작성한 기록입니다.");
        var journal = repository.saveAndFlush(new HikingRecord(member, activity, request));
        scores.rebuildLocked(member);
        return HikingRecordResponse.from(journal);
    }
    @Transactional
    // 본인 일지가 없으면 404다. 수정은 활동 연결을 유지하고 제목·내용·공개 여부만 교체한다.
    public HikingRecordResponse update(OAuthPrincipal p, Long id, JournalUpdateRequest request) {
        var member = members.current(p);
        members.requireComplete(member);
        scores.lock(member);
        var journal = repository.findByIdAndMemberId(id, member.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 공개 여부 변경을 반영한 뒤 일지 보너스를 포함한 점수를 재산정한다.
        journal.update(request);
        repository.flush();
        scores.rebuildLocked(member);
        return HikingRecordResponse.from(journal);
    }
    @Transactional
    // 삭제도 일지와 회원 ID를 함께 확인하므로 FE의 버튼 표시 여부와 무관하게 작성자만 삭제할 수 있다.
    public void delete(OAuthPrincipal p, Long id) {
        var member = members.current(p);
        members.requireComplete(member);
        scores.lock(member);
        var record = repository.findByIdAndMemberId(id, member.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 일지만 삭제·반영한 뒤 점수를 재산정하며, 연결된 산행 활동은 유지한다.
        repository.delete(record);
        repository.flush();
        scores.rebuildLocked(member);
    }
}
