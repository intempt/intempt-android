package com.intempt.core

import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.UtilsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any

/**
 * `withTryCatch` and `withTryCatchSuspend` catch `Throwable` so that an `Error` cannot escape
 * into the host app. `CancellationException` is a `Throwable`, so the broad catch also ate it:
 * it was logged as a failure, `null` was returned, and the coroutine carried on as though the
 * block had merely failed.
 *
 * That breaks structured concurrency in a way that produces no exception anywhere. A cancelled
 * scope keeps working, the parent believes cancellation completed, and the symptom is a leak.
 * The guards now rethrow `CancellationException` before the general branch.
 *
 * These tests assert the coroutine actually stops, not merely that an exception of the right
 * type came back — the latter would pass even if cancellation were being converted into an
 * ordinary failure somewhere upstream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UtilsServiceCancellationTest {
    private lateinit var logger: LoggerManagerService
    private lateinit var utils: UtilsService

    @Before
    fun setUp() {
        logger = mock(LoggerManagerService::class.java)
        utils = UtilsService(logger)
    }

    /**
     * The property that matters: cancelling the parent must actually stop the child.
     *
     * `reachedAfterSuspension` is the discriminator. If the guard swallows the
     * CancellationException, `withTryCatchSuspend` returns null, execution continues past it,
     * and the flag is set — so the assertion fails. If cancellation propagates, the coroutine
     * unwinds and the flag stays false.
     */
    @Test
    fun cancellingTheParentStopsWorkInsideTheSuspendGuard() =
        runTest {
            var enteredBlock = false
            var reachedAfterSuspension = false
            val blockStarted = CompletableDeferred<Unit>()

            val job =
                launch {
                    utils.withTryCatchSuspend("should not be logged") {
                        enteredBlock = true
                        blockStarted.complete(Unit)
                        awaitCancellation()
                    }
                    // Only reachable if the guard converted cancellation into a null return.
                    reachedAfterSuspension = true
                }

            blockStarted.await()
            job.cancelAndJoin()

            assertTrue("the block never ran, so this test proves nothing", enteredBlock)
            assertFalse(
                "cancellation was swallowed: execution continued past withTryCatchSuspend " +
                    "instead of unwinding, which is how a cancelled scope keeps working",
                reachedAfterSuspension,
            )
            assertTrue("the job should be cancelled", job.isCancelled)
        }

    /** Cancellation is not a failure, so it must not be reported as one. */
    @Test
    fun cancellationIsNotLoggedAsAnError() =
        runTest {
            val blockStarted = CompletableDeferred<Unit>()
            val job =
                launch {
                    utils.withTryCatchSuspend("cancellation must not be logged") {
                        blockStarted.complete(Unit)
                        awaitCancellation()
                    }
                }

            blockStarted.await()
            job.cancelAndJoin()

            verify(logger, never()).error(any())
        }

    /**
     * A CancellationException raised directly inside the block must come back out, rather than
     * being converted into a null.
     */
    @Test
    fun aCancellationExceptionInsideTheSuspendGuardPropagates() {
        val scope = TestScope(StandardTestDispatcher())
        var returned: Any? = "sentinel"

        val deferred =
            scope.async {
                returned =
                    utils.withTryCatchSuspend("should not be logged") {
                        throw CancellationException("cancelled inside the block")
                    }
            }
        scope.advanceUntilIdle()

        assertTrue("the CancellationException did not propagate", deferred.isCancelled)
        assertTrue(
            "the guard returned instead of throwing, so cancellation became a null result",
            returned == "sentinel",
        )
    }

    /**
     * The non-suspending guard is called from inside coroutines all over the SDK, so a
     * CancellationException reaches it too and must still propagate.
     */
    @Test
    fun theBlockingGuardAlsoRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            utils.withTryCatch<Unit>("should not be logged") {
                throw CancellationException("cancelled")
            }
        }
        verify(logger, never()).error(any())
    }

    /**
     * The reason the guards catch Throwable in the first place. Widening from Exception was
     * correct — an Error escaping into a host app is worse than a swallowed one — and this
     * pins that the widening survives the CancellationException fix.
     */
    @Test
    fun anErrorIsStillContainedRatherThanEscaping() {
        val result =
            utils.withTryCatch<String>("an Error must be contained") {
                throw NoSuchMethodError("a stripped method, as R8 would produce")
            }

        assertTrue("an Error must be swallowed and reported as null", result == null)
        verify(logger).error(any())
    }

    /** Same, for the suspend variant. */
    @Test
    fun anErrorInsideTheSuspendGuardIsStillContained() =
        runTest {
            val result =
                utils.withTryCatchSuspend<String>("an Error must be contained") {
                    withContext(Job() + kotlinx.coroutines.Dispatchers.Unconfined) {
                        throw OutOfMemoryError("an oversized payload, as a huge batch would produce")
                    }
                }

            assertTrue(result == null)
            verify(logger).error(any())
        }

    /** The ordinary path must be unaffected: a normal exception is still contained. */
    @Test
    fun anOrdinaryExceptionIsStillContained() {
        val result = utils.withTryCatch<String>("ordinary failure") { error("boom") }

        assertTrue(result == null)
        verify(logger).error(any())
    }

    /** And a successful block returns its value untouched. */
    @Test
    fun aSuccessfulBlockReturnsItsValue() =
        runTest {
            assertTrue(utils.withTryCatch("unused") { 42 } == 42)
            assertTrue(utils.withTryCatchSuspend("unused") { "ok" } == "ok")
            verify(logger, never()).error(any())
        }
}
