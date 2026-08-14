package com.nalssilog.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nalssilog.report.api.dto.LiveReportsResponse;
import com.nalssilog.report.application.dto.LiveReportData;
import com.nalssilog.report.application.dto.LocationSummary;
import com.nalssilog.report.application.dto.ReportActor;
import com.nalssilog.report.client.ImageStorageClient;
import com.nalssilog.report.client.LocationClient;
import com.nalssilog.report.client.MemberClient;
import com.nalssilog.report.domain.Precipitation;
import com.nalssilog.report.domain.Sunlight;
import com.nalssilog.report.domain.Temperature;
import com.nalssilog.report.repository.ThanksRepository;
import com.nalssilog.report.repository.WeatherReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

@SuppressWarnings("java:S5960")
class LiveReportServiceTest {

    private final WeatherReportRepository reportRepository = mock(WeatherReportRepository.class);
    private final ThanksRepository thanksRepository = mock(ThanksRepository.class);
    private final MemberClient memberClient = mock(MemberClient.class);
    private final LocationClient locationClient = mock(LocationClient.class);
    private final ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(
                reportRepository,
                thanksRepository,
                memberClient,
                locationClient,
                imageStorageClient,
                eventPublisher,
                new ReportConsentPolicy());
    }

    @Test
    void returnsLatestTwentyFourHourReportsWithLocationsAndGeneratedMessages() {
        Instant now = Instant.parse("2026-08-14T09:00:00Z");
        ReportActor viewer = ReportActor.member(7L);
        LiveReportData first = report(
                31L, 101L, Temperature.HOT, Precipitation.HEAVY, Sunlight.STRONG,
                Instant.parse("2026-08-14T08:30:00Z"));
        LiveReportData second = report(
                30L, 102L, Temperature.HOT, Precipitation.NONE, Sunlight.MODERATE,
                Instant.parse("2026-08-14T08:20:00Z"));

        when(reportRepository.findLiveReports(now.minus(Duration.ofHours(24)), viewer, 20))
                .thenReturn(List.of(first, second));
        when(locationClient.getLocations(List.of(101L, 102L))).thenReturn(Map.of(
                101L, location(101L, "서울특별시", "강남구", "역삼동"),
                102L, location(102L, "부산광역시", "해운대구", "우동")));

        LiveReportsResponse response = service.liveAt(viewer, now);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().getFirst())
                .satisfies(item -> {
                    assertThat(item.reportId()).isEqualTo("31");
                    assertThat(item.location().id()).isEqualTo("101");
                    assertThat(item.location().label()).isEqualTo("서울특별시 강남구 역삼동");
                    assertThat(item.location().shortLabel()).isEqualTo("강남구 역삼동");
                    assertThat(item.message()).isEqualTo("비가 많이 와요");
                    assertThat(item.createdAt()).isEqualTo(Instant.parse("2026-08-14T08:30:00Z"));
                });
        assertThat(response.items().get(1).message()).isEqualTo("더워요");
        verify(reportRepository).findLiveReports(now.minus(Duration.ofHours(24)), viewer, 20);
        verify(locationClient).getLocations(List.of(101L, 102L));
    }

    @Test
    void returnsEmptyItemsWithoutLoadingLocationsWhenNoLiveReportExists() {
        Instant now = Instant.parse("2026-08-14T09:00:00Z");

        when(reportRepository.findLiveReports(now.minus(Duration.ofHours(24)), null, 20))
                .thenReturn(List.of());

        LiveReportsResponse response = service.liveAt(null, now);

        assertThat(response.items()).isEmpty();
        verifyNoInteractions(locationClient);
    }

    private LiveReportData report(
            Long id,
            Long locationId,
            Temperature temperature,
            Precipitation precipitation,
            Sunlight sunlight,
            Instant createdAt
    ) {
        return new LiveReportData(
                id, locationId, temperature, precipitation, sunlight, createdAt);
    }

    private LocationSummary location(Long id, String sido, String sigungu, String dong) {
        return new LocationSummary(
                id,
                sido,
                sigungu,
                dong,
                String.join(" ", sido, sigungu, dong),
                String.join(" ", sigungu, dong));
    }
}
