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
    public static final String VERSION = "visit-500m-20m-daily1-v1";
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 인증에는 거리 500m 이상과 경과 시간 20분 이상이 모두 필요하다. 더 길거나 오래 걸려도 추가 점수는 없다.
    public static final double MIN_DISTANCE_METERS = 500;
    public static final long MIN_ELAPSED_MS = 20 * 60 * 1000L;
    private HikingScorePolicy() {}
    // 회원별로 분리한 입력을 받는다. GPS 증거·정상 도달 여부의 판정은 호출 전에 수행하며 여기서 좌표를 검증하지 않는다.
    public record Activity(long id, long mountainId, Instant startedAt, double distanceMeters,
                           long elapsedMs, boolean completed, boolean gpsRecorded) {}
    // 활동별 상태와 방문·정상 점수를 반환한다. 고정 정수 점수의 합이므로 반올림·절삭·실수의 정수 변환은 없다.
    public record Award(long activityId, long mountainId, LocalDate certificationDate,
                        String status, int visitPoints, int summitPoints) {
        public int points() { return visitPoints + summitPoints; }
        public boolean certified() { return "CERTIFIED".equals(status); }
    }
    // 전체 입력을 시작 시각·활동 ID 오름차순으로 계산하므로, 더 이른 활동이 추가되면 일일 인증과 첫 방문 결과가 바뀔 수 있다.
    public static List<Award> calculate(List<Activity> activities) {
        var sorted = activities.stream().sorted(Comparator.comparing(Activity::startedAt)
            .thenComparingLong(Activity::id)).toList();
        Set<LocalDate> dates = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        List<Award> awards = new ArrayList<>();
        for (var a : sorted) {
            // 시작 시각의 서울 날짜를 사용한다. 산과 무관하게 하루에 조건을 충족한 첫 활동 하나만 인증한다.
            LocalDate date = a.startedAt().atZone(ZONE).toLocalDate();
            // GPS 증거 없음→거리·시간 미달→일일 한도 순으로 제외하며, 제외된 활동의 두 점수는 모두 0이다.
            String status = !a.gpsRecorded() ? "GPS_REQUIRED"
                : !Double.isFinite(a.distanceMeters()) || a.distanceMeters() < MIN_DISTANCE_METERS
                  || a.elapsedMs() < MIN_ELAPSED_MS ? "BELOW_MINIMUM"
                : dates.contains(date) ? "DAILY_LIMIT" : "CERTIFIED";
            int visit = 0, summit = 0;
            if ("CERTIFIED".equals(status)) {
                dates.add(date);
                // 인증된 방문만 첫 방문 이력에 포함한다. 서로 다른 산의 첫 방문은 200점, 같은 산의 이후 방문은 100점이다.
                visit = visited.add(a.mountainId()) ? 200 : 100;
                // 정상 도달은 인증의 필수 조건이 아니며, 인증된 활동에서만 70점을 추가한다. 활동별 최대는 270점이다.
                summit = a.completed() ? 70 : 0;
            }
            awards.add(new Award(a.id(), a.mountainId(), date, status, visit, summit));
        }
        return List.copyOf(awards);
    }
}
