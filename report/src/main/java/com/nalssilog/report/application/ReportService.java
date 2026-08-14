package com.nalssilog.report.application;

import com.nalssilog.common.exception.NalssiLogException;
import com.nalssilog.common.response.CursorPage;
import com.nalssilog.member.application.dto.TermsAgreement;
import com.nalssilog.report.api.dto.LiveReportsResponse;
import com.nalssilog.report.api.dto.ReportResponse;
import com.nalssilog.report.api.dto.ThanksResponse;
import com.nalssilog.report.api.dto.WeatherStatsResponse;
import com.nalssilog.report.application.dto.AuthorInfo;
import com.nalssilog.report.application.dto.CreateReportCommand;
import com.nalssilog.report.application.dto.LiveReportData;
import com.nalssilog.report.application.dto.LocationSummary;
import com.nalssilog.report.application.dto.ReportActor;
import com.nalssilog.report.application.dto.ReportData;
import com.nalssilog.report.application.dto.WeatherStatsData;
import com.nalssilog.report.client.ImageStorageClient;
import com.nalssilog.report.client.LocationClient;
import com.nalssilog.report.client.MemberClient;
import com.nalssilog.report.domain.ActorType;
import com.nalssilog.report.domain.ReportErrorCode;
import com.nalssilog.report.domain.WeatherReport;
import com.nalssilog.report.domain.WeatherReportImage;
import com.nalssilog.report.domain.event.ReportDeletedEvent;
import com.nalssilog.report.repository.ThanksRepository;
import com.nalssilog.report.repository.WeatherReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private static final int PAGE_SIZE = 20;
    private static final Duration STATS_WINDOW = Duration.ofHours(3);
    private static final int LIVE_REPORT_LIMIT = 20;
    private static final Duration LIVE_REPORT_WINDOW = Duration.ofHours(24);

    private final WeatherReportRepository reportRepository;
    private final ThanksRepository thanksRepository;
    private final MemberClient memberClient;
    private final LocationClient locationClient;
    private final ImageStorageClient imageStorageClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ReportConsentPolicy reportConsentPolicy;

    @Transactional
    public ReportResponse create(ReportActor actor, CreateReportCommand command) {
        List<TermsAgreement> reportConsents = actor.type() == ActorType.ANONYMOUS
                ? reportConsentPolicy.validate(command.agreedTerms())
                : List.of();
        LocationSummary location = locationClient.getLocation(command.locationId());

        imageStorageClient.validateImageCount(command.imageKeys().size());
        command.imageKeys().forEach(imageStorageClient::validateKey);
        command.imageKeys().forEach(imageStorageClient::verifyUploaded);

        WeatherReport report = actor.type() == ActorType.MEMBER
                ? WeatherReport.ofMember(command.locationId(), actor.memberId(),
                command.temperature(), command.precipitation(), command.sunlight(), command.comment())
                : WeatherReport.ofAnonymous(command.locationId(), actor.anonymousKey(),
                command.temperature(), command.precipitation(), command.sunlight(), command.comment());

        report.addImages(command.imageKeys());
        Instant agreedAt = Instant.now();
        reportConsents.forEach(consent -> report.addConsent(consent.type(), consent.version(), agreedAt));
        ReportData data = reportRepository.save(report);

        return ReportResponse.of(data, location, resolveAuthor(data), 0L, false, true, resolveImageUrls(data));
    }

    public CursorPage<ReportResponse> list(Long locationId, String cursor, ReportActor viewer,
                                           List<ReportActor> ownershipActors) {
        CursorCodec.Cursor decoded = cursor == null ? null : CursorCodec.decode(cursor);
        Instant cursorTime = decoded == null ? null : decoded.createdAt();
        Long cursorId = decoded == null ? null : decoded.id();

        List<ReportData> fetched = reportRepository.findPage(
                locationId, cursorTime, cursorId, viewer, PAGE_SIZE + 1);
        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<ReportData> page = hasNext ? fetched.subList(0, PAGE_SIZE) : fetched;

        if (page.isEmpty()) {
            return CursorPage.last(List.of());
        }

        LocationSummary location = locationClient.getLocation(locationId);
        List<Long> reportIds = page.stream().map(ReportData::id).toList();
        Map<Long, Long> counts = thanksRepository.countByReportIds(reportIds);
        Set<Long> thanked = thanksRepository.thankedReportIds(reportIds, viewer);
        Map<Long, AuthorInfo> authors = memberClient.findActiveAuthors(
                page.stream()
                        .filter(data -> data.authorType() == ActorType.MEMBER)
                        .map(ReportData::authorMemberId)
                        .distinct()
                        .toList());

        List<ReportResponse> items = page.stream()
                .map(data -> ReportResponse.of(data, location, resolveAuthor(data, authors),
                        counts.getOrDefault(data.id(), 0L),
                        thanked.contains(data.id()),
                        isAuthor(data, ownershipActors),
                        resolveImageUrls(data)))
                .toList();

        ReportData lastItem = page.get(page.size() - 1);
        String nextCursor = hasNext ? CursorCodec.encode(lastItem.createdAt(), lastItem.id()) : null;

        return CursorPage.of(items, nextCursor);
    }

    public LiveReportsResponse live(ReportActor viewer) {
        return liveAt(viewer, Instant.now());
    }

    LiveReportsResponse liveAt(ReportActor viewer, Instant now) {
        List<LiveReportData> reports = reportRepository.findLiveReports(
                now.minus(LIVE_REPORT_WINDOW), viewer, LIVE_REPORT_LIMIT);

        if (reports.isEmpty()) {
            return new LiveReportsResponse(List.of());
        }

        Map<Long, LocationSummary> locations = locationClient.getLocations(
                reports.stream().map(LiveReportData::locationId).distinct().toList());
        List<LiveReportsResponse.Item> items = reports.stream()
                .map(report -> LiveReportsResponse.Item.of(
                        report,
                        locations.get(report.locationId()),
                        LiveReportMessageResolver.resolve(report)))
                .toList();

        return new LiveReportsResponse(items);
    }

    /**
     * 특정 회원이 작성한 제보 목록(내 제보 / 회원별 제보 공용). 여러 지역에 걸치므로 지역은 배치로 enrich 한다.
     * 대상 회원이 탈퇴 상태면 작성자 정보는 노출하지 않고 "익명 이웃"으로 렌더된다.
     */
    public CursorPage<ReportResponse> listByMember(Long memberId, String cursor, ReportActor viewer,
                                                   List<ReportActor> ownershipActors) {
        CursorCodec.Cursor decoded = cursor == null ? null : CursorCodec.decode(cursor);
        Instant cursorTime = decoded == null ? null : decoded.createdAt();
        Long cursorId = decoded == null ? null : decoded.id();

        List<ReportData> fetched = reportRepository.findMemberPage(
                memberId, cursorTime, cursorId, viewer, PAGE_SIZE + 1);
        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<ReportData> page = hasNext ? fetched.subList(0, PAGE_SIZE) : fetched;

        if (page.isEmpty()) {
            return CursorPage.last(List.of());
        }

        Map<Long, LocationSummary> locations = locationClient.getLocations(
                page.stream().map(ReportData::locationId).distinct().toList());
        List<Long> reportIds = page.stream().map(ReportData::id).toList();
        Map<Long, Long> counts = thanksRepository.countByReportIds(reportIds);
        Set<Long> thanked = thanksRepository.thankedReportIds(reportIds, viewer);
        AuthorInfo author = memberClient.findActiveAuthor(memberId).orElse(null);

        List<ReportResponse> items = page.stream()
                .map(data -> ReportResponse.of(data, locations.get(data.locationId()), author,
                        counts.getOrDefault(data.id(), 0L),
                        thanked.contains(data.id()),
                        isAuthor(data, ownershipActors),
                        resolveImageUrls(data)))
                .toList();

        ReportData lastItem = page.get(page.size() - 1);
        String nextCursor = hasNext ? CursorCodec.encode(lastItem.createdAt(), lastItem.id()) : null;

        return CursorPage.of(items, nextCursor);
    }

    /**
     * 지역 날씨 통계. 최근 {@link #STATS_WINDOW} 이내 제보들의 3축 분포 + 제보 수.
     * (locationClient.getLocation 이 유효하지 않은 지역이면 LOCATION_NOT_FOUND 를 던져 검증도 겸함)
     */
    public WeatherStatsResponse stats(Long locationId) {
        LocationSummary location = locationClient.getLocation(locationId);
        WeatherStatsData stats = reportRepository.statsSince(locationId, Instant.now().minus(STATS_WINDOW));

        return WeatherStatsResponse.of(location, stats);
    }

    public ReportResponse get(Long reportId, ReportActor viewer, List<ReportActor> ownershipActors) {
        ReportData data = reportRepository.getReport(reportId, viewer);
        LocationSummary location = locationClient.getLocation(data.locationId());
        long thanksCount = thanksRepository.count(reportId);
        boolean isThanked = viewer != null && thanksRepository.isThanked(reportId, viewer);

        return ReportResponse.of(
                data,
                location,
                resolveAuthor(data),
                thanksCount,
                isThanked,
                isAuthor(data, ownershipActors),
                resolveImageUrls(data));
    }

    @Transactional
    public ThanksResponse addThanks(Long reportId, ReportActor actor) {
        reportRepository.getReport(reportId, actor);
        thanksRepository.add(reportId, actor);

        return new ThanksResponse(thanksRepository.count(reportId), true);
    }

    @Transactional
    public ThanksResponse removeThanks(Long reportId, ReportActor actor) {
        reportRepository.getReport(reportId, actor);
        thanksRepository.remove(reportId, actor);

        return new ThanksResponse(thanksRepository.count(reportId), false);
    }

    @Transactional
    public void delete(Long reportId, List<ReportActor> actors) {
        WeatherReport report = reportRepository.getReportEntity(reportId);

        if (actors.stream().noneMatch(actor -> isAuthor(report, actor))) {
            throw new NalssiLogException(ReportErrorCode.REPORT_DELETE_FORBIDDEN);
        }

        List<String> imageKeys = report.getImages().stream()
                .map(WeatherReportImage::getStorageKey)
                .toList();

        thanksRepository.deleteAllByReportId(reportId);
        reportRepository.delete(report);
        eventPublisher.publishEvent(new ReportDeletedEvent(imageKeys));
    }

    private List<String> resolveImageUrls(ReportData data) {
        return data.imageKeys().stream()
                .map(imageStorageClient::toPublicUrl)
                .toList();
    }

    private AuthorInfo resolveAuthor(ReportData data) {
        if (data.authorType() != ActorType.MEMBER) {
            return null;
        }

        return memberClient.findActiveAuthor(data.authorMemberId()).orElse(null);
    }

    private AuthorInfo resolveAuthor(
            ReportData data,
            Map<Long, AuthorInfo> authors
    ) {
        if (data.authorType() != ActorType.MEMBER) {
            return null;
        }

        return authors.get(data.authorMemberId());
    }

    private boolean isAuthor(WeatherReport report, ReportActor actor) {
        if (report.getAuthorType() != actor.type()) {
            return false;
        }

        return actor.type() == ActorType.MEMBER
                ? Objects.equals(report.getAuthorMemberId(), actor.memberId())
                : Objects.equals(report.getAuthorAnonymousKey(), actor.anonymousKey());
    }

    private boolean isAuthor(ReportData data, List<ReportActor> actors) {
        return actors.stream().anyMatch(actor -> {
            if (data.authorType() != actor.type()) {
                return false;
            }

            return actor.type() == ActorType.MEMBER
                    ? Objects.equals(data.authorMemberId(), actor.memberId())
                    : Objects.equals(data.authorAnonymousKey(), actor.anonymousKey());
        });
    }
}
