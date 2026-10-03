package com.ggmount.hiking.dto;

import com.ggmount.hiking.domain.HikingActivity;
import java.time.Instant;
import java.time.LocalDate;

public record HikingActivityResponse(Long id, Long mountainId, String mountainName, Long courseId,
    Instant startedAt, Instant endedAt, LocalDate hikingDate, double distanceMeters,
    long elapsedMs, boolean completed, Long journalId) {
    public static HikingActivityResponse from(HikingActivity a, Long journalId) {
        return new HikingActivityResponse(a.getId(), a.getMountainId(), a.getMountainName(),
            a.getCourseId(), a.getStartedAt(), a.getEndedAt(), a.hikingDate(),
            a.getDistanceMeters(), a.getElapsedMs(), a.isCompleted(), journalId);
    }
}
