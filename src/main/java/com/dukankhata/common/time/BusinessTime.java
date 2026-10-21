package com.dukankhata.common.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class BusinessTime {
    private final ZoneId zone;

    public BusinessTime(@Value("${app.business-time-zone:Asia/Kolkata}") String zoneId) {
        this.zone = ZoneId.of(zoneId);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public InstantRange range(LocalDate from, LocalDate through) {
        return new InstantRange(from.atStartOfDay(zone).toInstant(),
                through.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public record InstantRange(Instant fromInclusive, Instant toExclusive) {}
}
