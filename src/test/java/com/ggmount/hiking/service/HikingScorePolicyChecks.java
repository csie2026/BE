package com.ggmount.hiking.service;

import java.time.Instant;
import java.util.*;

/** 운영 점수 정책을 직접 호출하는 외부 라이브러리 없는 검증 코드로, JUnit 래퍼와 독립 실행이 같은 사례를 사용한다.
 * 회원별 입력을 분리해 전달하며 DB·잠금·공개 일지 보너스는 검증 범위가 아니다.
 */
public final class HikingScorePolicyChecks {
    private static final Instant DAY = Instant.parse("2026-01-01T00:00:00Z");
    private static HikingScorePolicy.Activity a(long id, long mountain, long seconds, double meters, long ms, boolean completed, boolean gps, boolean verified) {
        return new HikingScorePolicy.Activity(id,mountain,DAY.plusSeconds(seconds),meters,ms,completed,gps,verified);
    }
    private static HikingScorePolicy.Activity valid(long id,long mountain,long seconds,boolean summit) {
        return a(id,mountain,seconds,500,1200000,summit,true,summit);
    }
    private static void equal(Object expected,Object actual) {
        if(!Objects.equals(expected,actual)) throw new AssertionError("expected="+expected+", actual="+actual);
    }
    private static HikingScorePolicy.Award award(HikingScorePolicy.Activity activity) {
        return HikingScorePolicy.calculate(List.of(activity)).getFirst();
    }
    public static void sixScenarios() {
        double[] meters={200,700,800,250,600,300}; long[] ms={300000,1500000,1800000,480000,600000,1500000};
        boolean[] summit={false,false,true,true,false,false};
        for(int i=0;i<6;i++) {
            var result=award(a(i+1,142,0,meters[i],ms[i],summit[i],true,summit[i]));
            equal(i==1||i==2||i==3 ? "CERTIFIED":"BELOW_MINIMUM",result.status());
            equal(summit[i]?70:0,result.summitPoints());
        }
    }
    public static void inclusiveThresholds() {
        equal("CERTIFIED",award(valid(1,1,0,false)).status());
        for(var input:List.of(a(1,1,0,499.999,1200000,false,true,false),a(1,1,0,500,1199999,false,true,false)))
            equal("BELOW_MINIMUM",award(input).status());
    }
    public static void completionFlagIsNotProof() {
        for(var input:List.of(a(1,142,0,250,480000,true,false,false),a(2,142,0,250,480000,true,true,false)))
            equal(false,award(input).certified());
        equal("CERTIFIED",award(a(3,142,0,0,0,true,false,true)).status());
        equal(270,award(a(3,142,0,0,0,true,false,true)).points());
    }
    public static void repeatedSameDay() {
        var awards=HikingScorePolicy.calculate(List.of(valid(1,142,0,true),valid(2,142,3600,true),valid(3,2,7200,false)));
        equal(List.of("CERTIFIED","CERTIFIED","CERTIFIED"),awards.stream().map(HikingScorePolicy.Award::status).toList());
        equal(640,awards.stream().mapToInt(HikingScorePolicy.Award::points).sum());
    }
    public static void weights() {
        var result=HikingScorePolicy.calculate(List.of(valid(1,1,0,false),valid(2,1,3600,true),valid(3,2,7200,true)));
        equal(List.of(200,170,270),result.stream().map(HikingScorePolicy.Award::points).toList());
    }
    public static void historicalFirstVisit() {
        equal(170,HikingScorePolicy.calculate(List.of(valid(1,142,0,true)),Set.of(142L)).getFirst().points());
    }
    public static void koreaDate() {
        var result=HikingScorePolicy.calculate(List.of(valid(1,1,53999,false),valid(2,1,54000,false)));
        equal("2026-01-01",result.get(0).certificationDate().toString()); equal("2026-01-02",result.get(1).certificationDate().toString());
    }
    public static void tiedStart() {
        var result=HikingScorePolicy.calculate(List.of(valid(8,1,0,true),valid(3,1,0,false)));
        equal(3L,result.getFirst().activityId()); equal(170,result.getLast().points());
    }
    public static void repeatable() {
        var input=List.of(valid(1,1,0,true),valid(2,1,3600,false));
        equal(HikingScorePolicy.calculate(input),HikingScorePolicy.calculate(input));
        equal(370,HikingScorePolicy.calculate(input).stream().mapToInt(HikingScorePolicy.Award::points).sum());
    }
    public static void membersIndependent() {
        equal(200,award(valid(1,1,0,false)).points()); equal(200,award(valid(2,1,0,false)).points());
    }
    public static void invalidDoesNotConsumeVisit() {
        var result=HikingScorePolicy.calculate(List.of(a(1,1,0,100,1000,true,false,false),valid(2,1,3600,false)));
        equal(0,result.getFirst().points()); equal(200,result.getLast().points());
    }
    public static void randomized() {
        var random=new Random(20261105);
        for(int run=0;run<500;run++) {
            var input=new ArrayList<HikingScorePolicy.Activity>();
            for(int i=1;i<=50;i++) input.add(a(i,1+random.nextInt(5),random.nextInt(86400),random.nextBoolean()?500:0,random.nextBoolean()?1200000:0,random.nextBoolean(),random.nextBoolean(),random.nextBoolean()));
            var expected=HikingScorePolicy.calculate(input);Collections.shuffle(input,random);equal(expected,HikingScorePolicy.calculate(input));
            var visited=new HashSet<Long>();
            for(var result:expected) {
                if(result.certified()) equal(visited.add(result.mountainId())?200:100,result.visitPoints());
                else equal(0,result.points());
            }
        }
    }
    public static void main(String[] args) {
        sixScenarios(); inclusiveThresholds(); completionFlagIsNotProof(); repeatedSameDay(); weights(); historicalFirstVisit();
        koreaDate(); tiedStart(); repeatable(); membersIndependent(); invalidDoesNotConsumeVisit(); randomized();
        System.out.println("PASS: 12 policy checks including A-F and 500 randomized histories");
    }
}
