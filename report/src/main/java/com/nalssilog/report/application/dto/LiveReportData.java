package com.nalssilog.report.application.dto;

import com.nalssilog.report.domain.Precipitation;
import com.nalssilog.report.domain.Sunlight;
import com.nalssilog.report.domain.Temperature;
import java.time.Instant;

public record LiveReportData(
        Long id,
        Long locationId,
        Temperature temperature,
        Precipitation precipitation,
        Sunlight sunlight,
        Instant createdAt
) {
}
