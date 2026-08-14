package com.nalssilog.report.api.dto;

import com.nalssilog.report.application.dto.LiveReportData;
import com.nalssilog.report.application.dto.LocationSummary;
import java.time.Instant;
import java.util.List;

public record LiveReportsResponse(List<Item> items) {

    public LiveReportsResponse {
        items = List.copyOf(items);
    }

    public record Item(
            String reportId,
            Location location,
            String message,
            Instant createdAt
    ) {

        public static Item of(LiveReportData report, LocationSummary location, String message) {
            return new Item(
                    String.valueOf(report.id()),
                    Location.from(location),
                    message,
                    report.createdAt());
        }
    }

    public record Location(
            String id,
            String sido,
            String sigungu,
            String dong,
            String label,
            String shortLabel
    ) {

        public static Location from(LocationSummary location) {
            return new Location(
                    String.valueOf(location.id()),
                    location.sido(),
                    location.sigungu(),
                    location.dong(),
                    location.label(),
                    location.shortLabel());
        }
    }
}
