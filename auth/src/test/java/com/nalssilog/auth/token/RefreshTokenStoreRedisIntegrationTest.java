package com.nalssilog.auth.token;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nalssilog.auth.token.RefreshTokenStore.RotationResult;
import com.nalssilog.auth.token.RefreshTokenStore.RotationStatus;
import com.nalssilog.auth.token.RefreshTokenStore.UsedToken;
import com.nalssilog.member.domain.Provider;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("java:S5960")
@Testcontainers
class RefreshTokenStoreRedisIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String USED_PREFIX = "auth:refresh-used:";
    private static final String RETRY_PREFIX = "auth:refresh-retry:";
    private static final String MEMBER_SESSIONS_PREFIX = "auth:member-sessions:";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RefreshTokenStore store;

    @BeforeAll
    static void connectToRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();

            return null;
        });
        store = new RefreshTokenStore(redisTemplate);
    }

    @Test
    void rotatesTokenAndPersistsReplacementTombstoneAndTtlsAtomically() {
        Duration tokenTtl = Duration.ofMinutes(5);
        Duration retryGrace = Duration.ofSeconds(5);
        long memberId = 1L;
        String sessionId = unique("session");
        SessionData current = session(unique("current"), sessionId, memberId);
        SessionData replacement = session(unique("replacement"), sessionId, memberId);
        String replacementToken = unique("replacement-token");
        store.save(current.tokenHash(), current, tokenTtl);

        RotationResult result = store.rotate(
                current.tokenHash(), replacementToken, replacement, tokenTtl, retryGrace);

        assertThat(result.status()).isEqualTo(RotationStatus.ROTATED);
        assertThat(result.replacementToken()).isEqualTo(replacementToken);
        assertThat(result.replacementHash()).isEqualTo(replacement.tokenHash());
        assertThat(result.memberId()).isEqualTo(memberId);
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.refreshTokenTtlMillis()).isEqualTo(tokenTtl.toMillis());

        assertThat(store.findSession(current.tokenHash())).isEmpty();
        assertThat(store.findSession(replacement.tokenHash())).contains(replacement);
        assertThat(store.findSessionsByMember(memberId)).containsExactly(replacement);

        UsedToken used = store.findUsedToken(current.tokenHash()).orElseThrow();
        assertThat(used.memberId()).isEqualTo(memberId);
        assertThat(used.sessionId()).isEqualTo(sessionId);
        assertThat(used.replacementHash()).isEqualTo(replacement.tokenHash());
        assertThat(redisTemplate.opsForValue().get(RETRY_PREFIX + current.tokenHash()))
                .isEqualTo(replacementToken);

        assertTtlWithin(REFRESH_PREFIX + replacement.tokenHash(), tokenTtl);
        assertTtlWithin(USED_PREFIX + current.tokenHash(), tokenTtl);
        assertTtlWithin(RETRY_PREFIX + current.tokenHash(), retryGrace);
        assertTtlWithin(MEMBER_SESSIONS_PREFIX + memberId, tokenTtl);
    }

    @Test
    void returnsOnlyOneReplacementWhenTheSameTokenIsRotatedConcurrently() throws Exception {
        int requestCount = 16;
        Duration tokenTtl = Duration.ofMinutes(5);
        Duration retryGrace = Duration.ofSeconds(5);
        long memberId = 2L;
        String sessionId = unique("session");
        SessionData current = session(unique("current"), sessionId, memberId);
        List<RotationCandidate> candidates = IntStream.range(0, requestCount)
                .mapToObj(index -> new RotationCandidate(
                        unique("replacement-token-" + index),
                        session(unique("replacement-" + index), sessionId, memberId)))
                .toList();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        store.save(current.tokenHash(), current, tokenTtl);

        List<RotationResult> results;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RotationResult>> futures = candidates.stream()
                    .map(candidate -> executor.submit(() -> {
                        ready.countDown();

                        if (!start.await(5, SECONDS)) {
                            throw new IllegalStateException("concurrent rotation start timed out");
                        }

                        return store.rotate(
                                current.tokenHash(),
                                candidate.token(),
                                candidate.session(),
                                tokenTtl,
                                retryGrace);
                    }))
                    .toList();

            boolean allRequestsReady = ready.await(5, SECONDS);
            start.countDown();
            assertThat(allRequestsReady).isTrue();

            results = futures.stream()
                    .map(RefreshTokenStoreRedisIntegrationTest::getResult)
                    .toList();
        }

        assertThat(results).filteredOn(result -> result.status() == RotationStatus.ROTATED).hasSize(1);
        assertThat(results).filteredOn(result -> result.status() == RotationStatus.RETRIED)
                .hasSize(requestCount - 1);

        RotationResult winner = results.stream()
                .filter(result -> result.status() == RotationStatus.ROTATED)
                .findFirst()
                .orElseThrow();
        assertThat(results).extracting(RotationResult::replacementToken)
                .containsOnly(winner.replacementToken());
        assertThat(results).extracting(RotationResult::replacementHash)
                .containsOnly(winner.replacementHash());
        assertThat(store.findSession(winner.replacementHash())).isPresent();
        assertThat(store.findSessionsByMember(memberId))
                .extracting(SessionData::tokenHash)
                .containsExactly(winner.replacementHash());
        assertThat(candidates)
                .filteredOn(candidate -> !candidate.session().tokenHash().equals(winner.replacementHash()))
                .allSatisfy(candidate -> assertThat(store.findSession(candidate.session().tokenHash())).isEmpty());
    }

    @Test
    void detectsReuseAfterRetryGraceAndRevokesTheWholeSession() {
        Duration tokenTtl = Duration.ofMinutes(5);
        Duration retryGrace = Duration.ofMillis(250);
        long memberId = 3L;
        String sessionId = unique("session");
        SessionData current = session(unique("current"), sessionId, memberId);
        SessionData replacement = session(unique("replacement"), sessionId, memberId);
        String replacementToken = unique("replacement-token");
        store.save(current.tokenHash(), current, tokenTtl);
        store.rotate(current.tokenHash(), replacementToken, replacement, tokenTtl, retryGrace);

        RotationResult immediateRetry = store.rotate(
                current.tokenHash(), "", replacement, tokenTtl, retryGrace);

        assertThat(immediateRetry.status()).isEqualTo(RotationStatus.RETRIED);
        assertThat(immediateRetry.replacementToken()).isEqualTo(replacementToken);

        await().atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> assertThat(redisTemplate.hasKey(RETRY_PREFIX + current.tokenHash())).isFalse());

        RotationResult reuse = store.rotate(
                current.tokenHash(), "", replacement, tokenTtl, retryGrace);

        assertThat(reuse.status()).isEqualTo(RotationStatus.REUSED);
        assertThat(reuse.memberId()).isEqualTo(memberId);
        assertThat(reuse.sessionId()).isEqualTo(sessionId);
        assertThat(store.revokeSession(memberId, sessionId, tokenTtl)).isEqualTo(1);
        assertThat(store.findSession(replacement.tokenHash())).isEmpty();
        assertThat(store.findSessionsByMember(memberId)).isEmpty();
        assertThat(store.isSessionRevoked(sessionId)).isTrue();

        RotationResult afterRevocation = store.rotate(
                current.tokenHash(), "", replacement, tokenTtl, retryGrace);
        assertThat(afterRevocation.status()).isEqualTo(RotationStatus.REVOKED);
    }

    private static RotationResult getResult(Future<RotationResult> future) {
        try {
            return future.get(10, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("concurrent rotation failed", e);
        }
    }

    private static SessionData session(String tokenHash, String sessionId, long memberId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        return new SessionData(
                tokenHash,
                sessionId,
                memberId,
                Provider.KAKAO,
                "Chrome / Windows",
                "127.0.0.1",
                now,
                now);
    }

    private static String unique(String prefix) {
        return prefix + '-' + UUID.randomUUID();
    }

    private static void assertTtlWithin(String key, Duration expectedMaximum) {
        Long ttlMillis = redisTemplate.getExpire(key, MILLISECONDS);

        assertThat(ttlMillis)
                .isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(expectedMaximum.toMillis());
    }

    private record RotationCandidate(String token, SessionData session) {
    }
}
