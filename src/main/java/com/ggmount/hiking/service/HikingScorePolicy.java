package com.ggmount.hiking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Pure, deterministic per-member policy. Raw GPS and summary values are still client reported. */
public final class HikingScorePolicy {
    public static final String VERSION = "visit-500m-20m-daily1-v1";
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final double MIN_DISTANCE_METERS = 500;
    public static final long MIN_ELAPSED_MS = 20 * 60 * 1000L;
    private HikingScorePolicy() {}
    public record Activity(long id, long mountainId, Instant startedAt, double distanceMeters,
                           long elapsedMs, boolean completed, boolean gpsRecorded) {}
    public record Award(long activityId, long mountainId, LocalDate certificationDate,
                        String status, int visitPoints, int summitPoints) {
        public int points() { return visitPoints + summitPoints; }
        public boolean certified() { return "CERTIFIED".equals(status); }
    }
    public static List<Award> calculate(List<Activity> activities) {
        var sorted = activities.stream().sorted(Comparator.comparing(Activity::startedAt)
            .thenComparingLong(Activity::id)).toList();
        Set<LocalDate> dates = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        List<Award> awards = new ArrayList<>();
        for (var a : sorted) {
            LocalDate date = a.startedAt().atZone(ZONE).toLocalDate();
            String status = !a.gpsRecorded() ? "GPS_REQUIRED"
                : !Double.isFinite(a.distanceMeters()) || a.distanceMeters() < MIN_DISTANCE_METERS
                  || a.elapsedMs() < MIN_ELAPSED_MS ? "BELOW_MINIMUM"
                : dates.contains(date) ? "DAILY_LIMIT" : "CERTIFIED";
            int visit = 0, summit = 0;
            if ("CERTIFIED".equals(status)) {
                dates.add(date);
                visit = visited.add(a.mountainId()) ? 200 : 100;
                summit = a.completed() ? 70 : 0;
            }
            awards.add(new Award(a.id(), a.mountainId(), date, status, visit, summit));
        }
        return List.copyOf(awards);
    }
}
