package com.ggmount.hiking.dto;
import java.time.LocalDate;
public record ActivityScoringResponse(String status, LocalDate certificationDate,
    int visitPoints, int summitPoints, String policyVersion) {}
