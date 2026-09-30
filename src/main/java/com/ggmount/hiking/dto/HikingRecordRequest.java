package com.ggmount.hiking.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record HikingRecordRequest(@NotBlank @Size(max=100) String mountainName,@NotBlank @Size(max=100) String title,@NotNull @Size(max=10000) String content,@NotNull @PastOrPresent LocalDate hikingDate,@NotNull Boolean isPublic) {}
