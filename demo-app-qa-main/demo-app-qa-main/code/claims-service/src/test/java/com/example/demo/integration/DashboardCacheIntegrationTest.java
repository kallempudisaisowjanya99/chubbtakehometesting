package com.example.demo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin dashboard statistics cached in a real Redis.
 *
 * <p>Verifies the cache contract end to end (key, TTL, hit/miss flag, bypass, flush) and documents one
 * behaviour worth knowing: creating a claim does <b>not</b> evict the cache, so cached stats can lag behind
 * the database for up to the 300 s TTL unless an admin bypasses or flushes the cache.
 */
class DashboardCacheIntegrationTest extends AbstractClaimsIntegrationTest {

    private static final String CACHE_KEY = "dashboard:stats";
    private static final long CACHE_TTL_SECONDS = 300;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void startWithEmptyCache() {
        assertThat(post("/api/admin/dashboard/flush-cache", adminToken, null).status()).isEqualTo(204);
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
    }

    @Test
    void should_CacheStatsInRedisWithTtl_When_FirstRequested() {
        JsonNode first = stats(false);

        assertThat(first.path("cacheHit").asBoolean()).isFalse();
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();
        assertThat(redis.getExpire(CACHE_KEY)).isBetween(1L, CACHE_TTL_SECONDS);
    }

    @Test
    void should_ServeFromCache_When_RequestedAgain() {
        JsonNode first = stats(false);

        JsonNode second = stats(false);

        assertThat(second.path("cacheHit").asBoolean()).isTrue();
        assertThat(second.path("totalClaims").asLong()).isEqualTo(first.path("totalClaims").asLong());
        assertThat(second.path("totalUsers").asLong()).isEqualTo(first.path("totalUsers").asLong());
    }

    @Test
    void should_ReturnFreshCountsFromDatabase_When_BypassingCache() {
        long before = stats(true).path("totalClaims").asLong();
        createClaim(claimantToken);

        JsonNode fresh = stats(true);

        assertThat(fresh.path("cacheHit").asBoolean()).isFalse();
        assertThat(fresh.path("totalClaims").asLong()).isEqualTo(before + 1);
        assertThat(fresh.path("totalClaims").asLong())
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM claims", Long.class));
    }

    @Test
    void should_KeepServingStaleStats_When_ClaimCreatedAfterCaching() {
        long cachedTotal = stats(false).path("totalClaims").asLong();

        createClaim(claimantToken);
        JsonNode afterNewClaim = stats(false);

        // Documented current behaviour (not evicted on write): the new claim is invisible until TTL/flush.
        assertThat(afterNewClaim.path("cacheHit").asBoolean()).isTrue();
        assertThat(afterNewClaim.path("totalClaims").asLong()).isEqualTo(cachedTotal);

        // ...and flushing makes it visible.
        post("/api/admin/dashboard/flush-cache", adminToken, null);
        assertThat(stats(false).path("totalClaims").asLong()).isEqualTo(cachedTotal + 1);
    }

    @Test
    void should_RemoveRedisKey_When_CacheFlushed() {
        stats(false);
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();

        ApiResponse flush = post("/api/admin/dashboard/flush-cache", adminToken, null);

        assertThat(flush.status()).isEqualTo(204);
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
        assertThat(stats(false).path("cacheHit").asBoolean()).isFalse();
    }

    private JsonNode stats(boolean bypassCache) {
        ApiResponse response = get("/api/admin/dashboard/stats" + (bypassCache ? "?bypassCache=true" : ""),
                adminToken);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }
}
