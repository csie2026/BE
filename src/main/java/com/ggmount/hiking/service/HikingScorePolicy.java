package com.ggmount.hiking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 한 회원의 활동 목록에서 방문·정상 도달 점수를 결정하는 계산 전용 정책이다. 저장·재산정 실행은 서비스가 담당한다.
 * 제출 GPS와 요약 값이 실제 이동을 증명하지는 않으며, 이 클래스는 전달받은 거리·시간·판정 값을 사용한다.
 * 산 높이·속도는 입력이나 가중치에 없고, 거리·소요 시간은 가산점이 아닌 인증 최소 조건으로만 사용한다.
 */
public final class HikingScorePolicy {
    // 계산 결과 저장과 응답에서 사용하는 정책 버전이며, 이 클래스에서 과거 버전으로 분기하지는 않는다.
    public static final String VERSION = "visit-general-or-verified-summit-multi-v2";
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 일반 인증은 거리 500m와 시간 20분이 모두 필요하다. 서버 검증 정상 도달은 별도 예외다.
    public static final double MIN_DISTANCE_METERS = 500;
    public static final long MIN_ELAPSED_MS = 20 * 60 * 1000L;
    private HikingScorePolicy() {}
    // 회원별로 분리한 입력을 받는다. GPS 증거·정상 도달 여부의 판정은 호출 전에 수행하며 여기서 좌표를 검증하지 않는다.
    public record Activity(long id, long mountainId, Instant startedAt, double distanceMeters,
                           long elapsedMs, boolean completed, boolean gpsRecorded, boolean summitVerified) {}
    // 활동별 상태와 방문·정상 점수를 반환한다. 고정 정수 점수의 합이므로 반올림·절삭·실수의 정수 변환은 없다.
    public record Award(long activityId, long mountainId, LocalDate certificationDate,
                        String status, int visitPoints, int summitPoints) {
        public int points() { return visitPoints + summitPoints; }
        public boolean certified() { return "CERTIFIED".equals(status); }
    }
    // 신규 정책 입력을 시작 시각·활동 ID 순서로 계산하며 과거 정책의 방문 이력은 보존한다.
    public static List<Award> calculate(List<Activity> activities) {
        return calculate(activities, Set.of());
    }
    public static List<Award> calculate(List<Activity> activities, Set<Long> historicalVisited) {
        var sorted = activities.stream().sorted(Comparator.comparing(Activity::startedAt)
            .thenComparingLong(Activity::id)).toList();
        Set<Long> visited = new HashSet<>(historicalVisited);
        List<Award> awards = new ArrayList<>();
        for (var a : sorted) {
            // 날짜는 표시용으로 유지하며 일일 제한에는 사용하지 않는다.
            LocalDate date = a.startedAt().atZone(ZONE).toLocalDate();
            // completed만으로 예외를 허용하지 않는다. 호출자는 서버 GPS 검증 이력도 확인해야 한다.
            boolean verifiedSummit = a.completed() && a.summitVerified();
            boolean general = a.gpsRecorded() && Double.isFinite(a.distanceMeters())
                && a.distanceMeters() >= MIN_DISTANCE_METERS && a.elapsedMs() >= MIN_ELAPSED_MS;
            String status = !a.gpsRecorded() && !verifiedSummit ? "GPS_REQUIRED"
                : general || verifiedSummit ? "CERTIFIED" : "BELOW_MINIMUM";
            int visit = 0, summit = 0;
            if ("CERTIFIED".equals(status)) {
                // 인증된 방문만 첫 방문 이력에 포함한다. 서로 다른 산의 첫 방문은 200점, 같은 산의 이후 방문은 100점이다.
                visit = visited.add(a.mountainId()) ? 200 : 100;
                // 정상 도달은 인증의 필수 조건이 아니며, 인증된 활동에서만 70점을 추가한다. 활동별 최대는 270점이다.
                summit = a.completed() && a.summitVerified() ? 70 : 0;
            }
            awards.add(new Award(a.id(), a.mountainId(), date, status, visit, summit));
        }
        return List.copyOf(awards);
    }
}
