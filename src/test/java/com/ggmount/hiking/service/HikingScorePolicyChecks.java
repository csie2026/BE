package com.ggmount.hiking.service;

import java.time.Instant;
import java.util.*;

/** 운영 점수 정책을 직접 호출하는 외부 라이브러리 없는 검증 코드로, JUnit 래퍼와 독립 실행이 같은 사례를 사용한다.
 * 회원별 입력을 분리해 전달하며 DB·잠금·공개 일지 보너스는 검증 범위가 아니다.
 */
public final class HikingScorePolicyChecks {
    private static final Instant DAY = Instant.parse("2026-01-01T00:00:00Z");
    private static HikingScorePolicy.Activity a(long id, long mountain, long seconds, double meters, long ms, boolean completed, boolean gps) {
        return new HikingScorePolicy.Activity(id, mountain, DAY.plusSeconds(seconds), meters, ms, completed, gps);
    }
    private static HikingScorePolicy.Activity valid(long id, long mountain, long seconds, boolean completed) { return a(id,mountain,seconds,500,1200000,completed,true); }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
    public static void fourPointCases() {
        for (boolean first : List.of(true, false)) for (boolean completed : List.of(true, false)) {
            var target = valid(2, 1, 86400, completed);
            var awards = HikingScorePolicy.calculate(first ? List.of(target) : List.of(valid(1,1,0,false),target));
            var award = awards.get(awards.size()-1);
            equal(first ? 200 : 100, award.visitPoints()); equal(completed ? 70 : 0, award.summitPoints());
        }
    }
    public static void inclusiveThresholds() {
        equal("CERTIFIED", HikingScorePolicy.calculate(List.of(valid(1,1,0,false))).get(0).status());
        for (var input : List.of(a(1,1,0,499.999,1200000,true,true), a(1,1,0,500,1199999,true,true), a(1,1,0,Double.NaN,1200000,true,true))) {
            var award = HikingScorePolicy.calculate(List.of(input)).get(0); equal("BELOW_MINIMUM",award.status()); equal(0,award.points());
        }
    }
    public static void invalidDoesNotReserveDayOrFirstVisit() {
        var awards = HikingScorePolicy.calculate(List.of(a(1,1,0,500,1200000,true,false),a(2,1,3600,0,0,true,true),valid(3,1,7200,false)));
        equal("GPS_REQUIRED",awards.get(0).status()); equal("BELOW_MINIMUM",awards.get(1).status()); equal(200,awards.get(2).points());
    }
    public static void oneCertificationAcrossAllMountains() {
        var awards = HikingScorePolicy.calculate(List.of(valid(1,1,0,true),valid(2,2,3600,true),valid(3,1,7200,true)));
        equal(List.of("CERTIFIED","DAILY_LIMIT","DAILY_LIMIT"),awards.stream().map(HikingScorePolicy.Award::status).toList());
        equal(270,awards.stream().mapToInt(HikingScorePolicy.Award::points).sum());
    }
    public static void midnightUsesKoreaStartDate() {
        var awards = HikingScorePolicy.calculate(List.of(valid(1,1,53999,false),valid(2,1,54000,false)));
        equal("2026-01-01", awards.get(0).certificationDate().toString()); equal("2026-01-02",awards.get(1).certificationDate().toString());
        equal(200,awards.get(0).points()); equal(100,awards.get(1).points());
    }
    public static void lateEarlierActivityReordersDailyWinner() {
        var late = valid(2,2,3600,true); equal(270,HikingScorePolicy.calculate(List.of(late)).get(0).points());
        var rebuilt = HikingScorePolicy.calculate(List.of(late,valid(1,1,0,false)));
        equal(1L,rebuilt.get(0).activityId()); equal(200,rebuilt.get(0).points()); equal("DAILY_LIMIT",rebuilt.get(1).status());
    }
    public static void excludedMountainDoesNotConsumeFirstVisit() {
        var awards = HikingScorePolicy.calculate(List.of(valid(1,1,0,false),valid(2,2,3600,true),valid(3,2,86400,false)));
        equal(200,awards.get(2).points());
    }
    public static void tiedStartUsesActivityId() {
        var awards = HikingScorePolicy.calculate(List.of(valid(8,2,0,true),valid(3,1,0,false)));
        equal(3L,awards.get(0).activityId()); equal("DAILY_LIMIT",awards.get(1).status());
    }
    public static void rebuildIsRepeatable() {
        var input = List.of(valid(1,1,0,true),valid(2,1,86400,false));
        equal(HikingScorePolicy.calculate(input),HikingScorePolicy.calculate(input));
        equal(370,HikingScorePolicy.calculate(input).stream().mapToInt(HikingScorePolicy.Award::points).sum());
    }
    public static void membersAreIndependentInputs() {
        equal(200,HikingScorePolicy.calculate(List.of(valid(1,1,0,false))).get(0).points());
        equal(200,HikingScorePolicy.calculate(List.of(valid(2,1,0,false))).get(0).points());
    }
    public static void unverifiedHistoryIsNotInvented() {
        var result = HikingScorePolicy.calculate(List.of(a(1,1,0,1000,3600000,true,false),valid(2,1,86400,false)));
        equal(0,result.get(0).points()); equal(200,result.get(1).points());
    }
    public static void randomizedRebuildOrderAndDailyUniqueness() {
        // 고정 난수 시드로 이력을 구성하고 입력 순서를 섞어도 같은 결과인지 확인한다. 독립된 다른 점수식과 대조하지는 않는다.
        var random = new Random(20261105);
        for (int run=0;run<500;run++) {
            var input = new ArrayList<HikingScorePolicy.Activity>();
            for(int i=1;i<=50;i++) input.add(a(i,1+random.nextInt(5),random.nextInt(86400*8),random.nextBoolean()?500:0,random.nextBoolean()?1200000:0,random.nextBoolean(),random.nextBoolean()));
            var expected = HikingScorePolicy.calculate(input); Collections.shuffle(input,random); equal(expected,HikingScorePolicy.calculate(input));
            var dates = new HashSet<java.time.LocalDate>(); var mountains = new HashSet<Long>();
            for(var award:expected) {
                if(award.certified()) { if(!dates.add(award.certificationDate())) throw new AssertionError("duplicate day"); equal(mountains.add(award.mountainId())?200:100,award.visitPoints()); }
                else equal(0,award.points());
            }
        }
    }
    public static void main(String[] args) {
        fourPointCases(); inclusiveThresholds(); invalidDoesNotReserveDayOrFirstVisit(); oneCertificationAcrossAllMountains();
        midnightUsesKoreaStartDate(); lateEarlierActivityReordersDailyWinner(); excludedMountainDoesNotConsumeFirstVisit();
        tiedStartUsesActivityId(); rebuildIsRepeatable(); membersAreIndependentInputs(); unverifiedHistoryIsNotInvented();
        randomizedRebuildOrderAndDailyUniqueness();
        System.out.println("PASS: 12 production policy checks, including 500 randomized histories");
    }
}
