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
@Service @Transactional(readOnly=true)
public class HikingRecordService {
 private final HikingRecordRepository repository; private final MemberService members;
 public HikingRecordService(HikingRecordRepository repository,MemberService members) { this.repository=repository; this.members=members; }
 public List<HikingRecordResponse> mine(OAuthPrincipal p) { return repository.findByMemberIdOrderByHikingDateDescIdDesc(members.current(p).getId()).stream().map(HikingRecordResponse::from).toList(); }
 public HikingRecordResponse detail(OAuthPrincipal p,Long id) {
  var member=members.current(p);
  var record=repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  if(!record.isPublicRecord() && !record.getMember().getId().equals(member.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  return HikingRecordResponse.from(record);
 }
 public List<HikingRecordResponse> publicRecords(Long userId) {
  if(userId==null) return repository.findTop100ByPublicRecordTrueOrderByHikingDateDescIdDesc().stream().map(HikingRecordResponse::from).toList();
  members.requireComplete(members.find(userId));
  return repository.findByMemberIdAndPublicRecordTrueOrderByHikingDateDescIdDesc(userId).stream().map(HikingRecordResponse::from).toList();
 }
 @Transactional public HikingRecordResponse save(OAuthPrincipal p,Long id,HikingRecordRequest request) {
  var member=members.current(p); members.requireComplete(member);
  HikingRecord record=id==null ? new HikingRecord(member,request) : repository.findByIdAndMemberId(id,member.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  record.update(request); return HikingRecordResponse.from(repository.save(record));
 }
 @Transactional public void delete(OAuthPrincipal p,Long id) {
  var member=members.current(p); members.requireComplete(member);
  var record=repository.findByIdAndMemberId(id,member.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  repository.delete(record);
 }
}
