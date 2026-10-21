package com.dukankhata.common.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeTest {
    private final BusinessTime businessTime = new BusinessTime("Asia/Kolkata");

    @Test
    void dateRangeUsesKolkataMidnightAndExclusiveNextDayAcrossMonthEnd() {
        var range = businessTime.range(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-01"));

        assertThat(range.fromInclusive()).isEqualTo(Instant.parse("2026-03-30T18:30:00Z"));
        assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-04-01T18:30:00Z"));
    }
}
