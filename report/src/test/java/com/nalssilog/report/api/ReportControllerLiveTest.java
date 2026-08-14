package com.nalssilog.report.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nalssilog.report.api.dto.LiveReportsResponse;
import com.nalssilog.report.application.ActorRestrictionService;
import com.nalssilog.report.application.ReportFlagService;
import com.nalssilog.report.application.ReportRateLimiter;
import com.nalssilog.report.application.ReportService;
import com.nalssilog.report.application.dto.ReportActor;
import com.nalssilog.report.config.ReportActorResolver;
import com.nalssilog.report.config.ReportClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings("java:S5960")
class ReportControllerLiveTest {

    private final ReportService reportService = mock(ReportService.class);
    private final ReportActorResolver actorResolver = mock(ReportActorResolver.class);
    private final ReportController controller = new ReportController(
            reportService,
            actorResolver,
            mock(ReportClientIpResolver.class),
            mock(ReportRateLimiter.class),
            mock(ActorRestrictionService.class),
            mock(ReportFlagService.class));
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

    @Test
    void reflectsOptionalMemberAuthenticationAndDisablesSharedCaching() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ReportActor viewer = ReportActor.member(7L);
        LiveReportsResponse body = new LiveReportsResponse(List.of());

        when(actorResolver.resolveForRead(7L, request)).thenReturn(viewer);
        when(reportService.live(viewer)).thenReturn(body);

        ResponseEntity<LiveReportsResponse> response = controller.live(7L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isSameAs(body);
        verify(actorResolver).resolveForRead(7L, request);
        verify(reportService).live(viewer);
    }

    @Test
    void returnsThePublicLiveResponseContractForAnAnonymousRequest() throws Exception {
        LiveReportsResponse body = new LiveReportsResponse(List.of(
                new LiveReportsResponse.Item(
                        "987654321098765432",
                        new LiveReportsResponse.Location(
                                "123456789012345678",
                                "서울특별시",
                                "강남구",
                                "역삼동",
                                "서울특별시 강남구 역삼동",
                                "강남구 역삼동"),
                        "비가 많이 와요",
                        Instant.parse("2026-08-14T08:30:00Z"))));

        when(actorResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(null);
        when(reportService.live(null)).thenReturn(body);

        mockMvc.perform(get("/api/reports/live"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.items[0].reportId").value("987654321098765432"))
                .andExpect(jsonPath("$.items[0].location.id").value("123456789012345678"))
                .andExpect(jsonPath("$.items[0].location.shortLabel").value("강남구 역삼동"))
                .andExpect(jsonPath("$.items[0].message").value("비가 많이 와요"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-14T08:30:00Z"));
    }
}
