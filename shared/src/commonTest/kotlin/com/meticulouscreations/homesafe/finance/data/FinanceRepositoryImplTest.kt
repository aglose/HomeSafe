package com.meticulouscreations.homesafe.finance.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TimedCache] backs every repository call: a short-lived read-through cache that also shares an
 * in-flight fetch between simultaneous callers, so the Markets and Risk tabs asking for the same
 * series as they open don't double the network calls.
 */
class FinanceRepositoryImplTest {

    @Test
    fun getReturnsTheValueWithinMaxAgeAndNullOnceItExpires() {
        val cache = TimedCache<String, Int>()
        cache.put("k", 1)
        assertEquals(1, cache.get("k", maxAgeMillis = 60_000), "freshly put, well within any reasonable max age")
        // A negative max age is always already expired; real wall-clock time can't be rewound in
        // a unit test, so this is the deterministic way to force the "stale" branch.
        assertNull(cache.get("k", maxAgeMillis = -1))
        assertNull(cache.get("missing-key", maxAgeMillis = 60_000))
    }

    @Test
    fun concurrentLoadsForTheSameKeyShareOneFetchAndOneResult() = runTest {
        val cache = TimedCache<String, Int>()
        var fetchCount = 0
        val first = async {
            cache.load("k") {
                fetchCount++
                delay(10)
                Result.success(42)
            }
        }
        val second = async {
            cache.load("k") {
                fetchCount++
                delay(10)
                Result.success(99)
            }
        }
        advanceUntilIdle()

        assertEquals(42, first.await().getOrThrow())
        assertEquals(42, second.await().getOrThrow(), "the second caller gets the first caller's answer, never its own")
        assertEquals(1, fetchCount, "the second caller's fetch lambda never runs at all")
    }

    @Test
    fun loadsForDifferentKeysDoNotShareAFetch() = runTest {
        val cache = TimedCache<String, Int>()
        var fetchCount = 0
        val a = async {
            cache.load("a") {
                fetchCount++
                delay(10)
                Result.success(1)
            }
        }
        val b = async {
            cache.load("b") {
                fetchCount++
                delay(10)
                Result.success(2)
            }
        }
        advanceUntilIdle()

        assertEquals(1, a.await().getOrThrow())
        assertEquals(2, b.await().getOrThrow())
        assertEquals(2, fetchCount)
    }

    @Test
    fun aFailedFetchIsNotCachedSoTheNextLoadTriesAgain() = runTest {
        val cache = TimedCache<String, Int>()
        var attempt = 0
        suspend fun fetch(): Result<Int> {
            attempt++
            return if (attempt == 1) Result.failure(IllegalStateException("boom")) else Result.success(7)
        }

        val first = cache.load("k", ::fetch)
        assertTrue(first.isFailure)
        assertNull(cache.get("k", maxAgeMillis = 60_000), "a failure must not poison the cache for later reads")

        val second = cache.load("k", ::fetch)
        assertEquals(7, second.getOrThrow())
        assertEquals(2, attempt, "the second load actually fetched again rather than replaying the failure")
    }
}
