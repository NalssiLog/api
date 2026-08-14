package com.nalssilog.report.repository;

import static com.nalssilog.report.domain.QWeatherReport.weatherReport;
import static com.nalssilog.report.domain.QWeatherReportImage.weatherReportImage;
import static com.nalssilog.report.domain.QActorBlock.actorBlock;

import com.nalssilog.common.exception.NalssiLogException;
import com.nalssilog.report.application.dto.LiveReportData;
import com.nalssilog.report.application.dto.PopularLocationAggregate;
import com.nalssilog.report.application.dto.ReportData;
import com.nalssilog.report.application.dto.ReportActor;
import com.nalssilog.report.application.dto.WeatherStatsData;
import com.nalssilog.report.domain.ActorType;
import com.nalssilog.report.domain.Precipitation;
import com.nalssilog.report.domain.ModerationStatus;
import com.nalssilog.report.domain.ReportErrorCode;
import com.nalssilog.report.domain.Sunlight;
import com.nalssilog.report.domain.Temperature;
import com.nalssilog.report.domain.WeatherReport;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateTimeExpression;
import com.querydsl.core.types.dsl.EnumPath;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.JPAExpressions;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 서비스 호출용 WeatherReport 저장소.
 * 단순 조회는 Spring Data JPA에 위임하고, 복합 조회와 집계는 QueryDSL로 처리한다.
 */
@Repository
@RequiredArgsConstructor
public class WeatherReportRepository {

    private final WeatherReportJpaRepository weatherReportJpaRepository;
    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    public ReportData save(WeatherReport report) {
        return ReportData.of(weatherReportJpaRepository.save(report));
    }

    /**
     * 탈퇴 회원의 제보를 익명화한다(삭제 없이 작성자만 ANONYMOUS 로). 반환값은 익명화된 제보 수.
     */
    public int anonymizeAuthor(Long memberId) {
        entityManager.flush();

        long affectedRows = queryFactory
                .update(weatherReport)
                .set(weatherReport.authorType, ActorType.ANONYMOUS)
                .setNull(weatherReport.authorMemberId)
                .set(weatherReport.authorAnonymousKey, "withdrawn-" + memberId)
                .where(
                        weatherReport.authorType.eq(ActorType.MEMBER),
                        weatherReport.authorMemberId.eq(memberId)
                )
                .execute();

        entityManager.clear();

        return Math.toIntExact(affectedRows);
    }

    public ReportData getReport(Long reportId) {
        return getReport(reportId, null);
    }

    public ReportData getReport(Long reportId, ReportActor viewer) {
        return ReportData.of(getVisibleReportEntity(reportId, viewer));
    }

    /** 삭제처럼 관리 엔티티가 필요한 쓰기 유스케이스 전용. 반드시 트랜잭션 안에서 사용한다. */
    public WeatherReport getReportEntity(Long reportId) {
        return findReportEntity(reportId, false, null);
    }

    public WeatherReport getVisibleReportEntity(Long reportId) {
        return getVisibleReportEntity(reportId, null);
    }

    public WeatherReport getVisibleReportEntity(Long reportId, ReportActor viewer) {
        return findReportEntity(reportId, true, viewer);
    }

    private WeatherReport findReportEntity(Long reportId, boolean visibleOnly, ReportActor viewer) {
        WeatherReport report = queryFactory
                .selectFrom(weatherReport)
                .distinct()
                .leftJoin(weatherReport.images, weatherReportImage)
                .fetchJoin()
                .where(
                        weatherReport.id.eq(reportId),
                        visibleOnly ? weatherReport.moderationStatus.eq(ModerationStatus.VISIBLE) : null,
                        visibleOnly ? withoutBlockRelation(viewer) : null)
                .fetchOne();

        if (report == null) {
            throw new NalssiLogException(ReportErrorCode.REPORT_NOT_FOUND);
        }

        return report;
    }

    public void delete(WeatherReport report) {
        weatherReportJpaRepository.delete(report);
    }

    public List<ReportData> findPage(Long locationId, Instant cursorTime, Long cursorId, int limit) {
        return findPage(locationId, cursorTime, cursorId, null, limit);
    }

    public List<ReportData> findPage(
            Long locationId,
            Instant cursorTime,
            Long cursorId,
            ReportActor viewer,
            int limit
    ) {
        List<WeatherReport> reports = queryFactory
                .selectFrom(weatherReport)
                .where(
                        weatherReport.locationId.eq(locationId),
                        weatherReport.moderationStatus.eq(ModerationStatus.VISIBLE),
                        cursorTime == null ? null : beforeCursor(cursorTime, cursorId),
                        withoutBlockRelation(viewer))
                .orderBy(weatherReport.createdAt.desc(), weatherReport.id.desc())
                .limit(limit)
                .fetch();

        return fetchImages(reports).stream()
                .map(ReportData::of)
                .toList();
    }

    public List<LiveReportData> findLiveReports(
            Instant since,
            ReportActor viewer,
            int limit
    ) {
        return queryFactory
                .select(
                        weatherReport.id,
                        weatherReport.locationId,
                        weatherReport.temperature,
                        weatherReport.precipitation,
                        weatherReport.sunlight,
                        weatherReport.createdAt)
                .from(weatherReport)
                .where(
                        weatherReport.moderationStatus.eq(ModerationStatus.VISIBLE),
                        weatherReport.createdAt.goe(since),
                        withoutBlockRelation(viewer))
                .orderBy(weatherReport.createdAt.desc(), weatherReport.id.desc())
                .limit(limit)
                .fetch()
                .stream()
                .map(row -> new LiveReportData(
                        row.get(weatherReport.id),
                        row.get(weatherReport.locationId),
                        row.get(weatherReport.temperature),
                        row.get(weatherReport.precipitation),
                        row.get(weatherReport.sunlight),
                        row.get(weatherReport.createdAt)))
                .toList();
    }

    public List<ReportData> findMemberPage(Long memberId, Instant cursorTime, Long cursorId, int limit) {
        return findMemberPage(memberId, cursorTime, cursorId, null, limit);
    }

    public List<ReportData> findMemberPage(
            Long memberId,
            Instant cursorTime,
            Long cursorId,
            ReportActor viewer,
            int limit
    ) {
        List<WeatherReport> reports = queryFactory
                .selectFrom(weatherReport)
                .where(
                        weatherReport.authorType.eq(ActorType.MEMBER),
                        weatherReport.authorMemberId.eq(memberId),
                        weatherReport.moderationStatus.eq(ModerationStatus.VISIBLE),
                        cursorTime == null ? null : beforeCursor(cursorTime, cursorId),
                        withoutBlockRelation(viewer))
                .orderBy(weatherReport.createdAt.desc(), weatherReport.id.desc())
                .limit(limit)
                .fetch();

        return fetchImages(reports).stream()
                .map(ReportData::of)
                .toList();
    }

    public List<PopularLocationAggregate> findPopularLocationAggregates(
            Instant windowStartedAt,
            Instant windowEndedAt,
            int limit
    ) {
        NumberExpression<Long> uniqueReporterCount = weatherReport.authorMemberId.countDistinct()
                .add(weatherReport.authorAnonymousKey.countDistinct());
        NumberExpression<Long> reportCount = weatherReport.id.count();
        DateTimeExpression<Instant> latestReportAt = weatherReport.createdAt.max();
        List<Tuple> rows = queryFactory
                .select(
                        weatherReport.locationId,
                        uniqueReporterCount,
                        reportCount,
                        latestReportAt)
                .from(weatherReport)
                .where(
                        weatherReport.createdAt.goe(windowStartedAt),
                        weatherReport.createdAt.lt(windowEndedAt),
                        weatherReport.moderationStatus.eq(ModerationStatus.VISIBLE))
                .groupBy(weatherReport.locationId)
                .orderBy(
                        uniqueReporterCount.desc(),
                        reportCount.desc(),
                        latestReportAt.desc(),
                        weatherReport.locationId.asc())
                .limit(limit)
                .fetch();

        return rows.stream()
                .map(row -> new PopularLocationAggregate(
                        row.get(weatherReport.locationId),
                        value(row.get(uniqueReporterCount)),
                        value(row.get(reportCount)),
                        row.get(latestReportAt)))
                .toList();
    }

    /** 최근({@code since} 이후) 제보의 3축 분포 + 제보 수 집계. */
    public WeatherStatsData statsSince(Long locationId, Instant since) {
        long reportCount = weatherReportJpaRepository
                .countByLocationIdAndModerationStatusAndCreatedAtGreaterThanEqual(
                        locationId, ModerationStatus.VISIBLE, since);

        return new WeatherStatsData(
                reportCount,
                countByAxis(weatherReport.temperature, Temperature.class, locationId, since),
                countByAxis(weatherReport.precipitation, Precipitation.class, locationId, since),
                countByAxis(weatherReport.sunlight, Sunlight.class, locationId, since)
        );
    }

    /**
     * 컬렉션 fetch join에 페이지 제한을 직접 적용하면 메모리 페이징이 발생할 수 있다.
     * 먼저 루트 엔티티를 제한한 뒤 선택된 ID만 이미지와 fetch join하고 기존 순서를 복원한다.
     */
    private List<WeatherReport> fetchImages(List<WeatherReport> reports) {
        if (reports.isEmpty()) {
            return List.of();
        }

        List<Long> reportIds = reports.stream()
                .map(WeatherReport::getId)
                .toList();
        Map<Long, WeatherReport> fetchedById = queryFactory
                .selectFrom(weatherReport)
                .distinct()
                .leftJoin(weatherReport.images, weatherReportImage)
                .fetchJoin()
                .where(weatherReport.id.in(reportIds))
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        WeatherReport::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        return reportIds.stream()
                .map(fetchedById::get)
                .toList();
    }

    private BooleanExpression beforeCursor(Instant cursorTime, Long cursorId) {
        return weatherReport.createdAt.lt(cursorTime)
                .or(weatherReport.createdAt.eq(cursorTime).and(weatherReport.id.lt(cursorId)));
    }

    private <E extends Enum<E>> Map<E, Long> countByAxis(
            EnumPath<E> axis, Class<E> type, Long locationId, Instant since) {
        NumberExpression<Long> count = weatherReport.id.count();
        List<Tuple> rows = queryFactory
                .select(axis, count)
                .from(weatherReport)
                .where(
                        weatherReport.locationId.eq(locationId),
                        weatherReport.createdAt.goe(since),
                        weatherReport.moderationStatus.eq(ModerationStatus.VISIBLE)
                )
                .groupBy(axis)
                .fetch();

        Map<E, Long> counts = new EnumMap<>(type);

        rows.forEach(row -> counts.put(row.get(axis), row.get(count)));

        return counts;
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    BooleanExpression withoutBlockRelation(ReportActor viewer) {
        if (viewer == null || viewer.type() != ActorType.MEMBER) {
            return null;
        }

        StringExpression authorKey = new CaseBuilder()
                .when(weatherReport.authorType.eq(ActorType.MEMBER))
                .then(weatherReport.authorMemberId.stringValue())
                .otherwise(weatherReport.authorAnonymousKey);

        BooleanExpression viewerBlocksAuthor =
                actorBlock.blockerType.eq(viewer.type())
                        .and(actorBlock.blockerKey.eq(viewer.actorKey()))
                        .and(actorBlock.blockedType.eq(weatherReport.authorType))
                        .and(actorBlock.blockedKey.eq(authorKey));
        BooleanExpression authorBlocksViewer =
                actorBlock.blockerType.eq(weatherReport.authorType)
                        .and(actorBlock.blockerKey.eq(authorKey))
                        .and(actorBlock.blockedType.eq(viewer.type()))
                        .and(actorBlock.blockedKey.eq(viewer.actorKey()));

        return JPAExpressions.selectOne()
                .from(actorBlock)
                .where(viewerBlocksAuthor.or(authorBlocksViewer))
                .notExists();
    }
}
