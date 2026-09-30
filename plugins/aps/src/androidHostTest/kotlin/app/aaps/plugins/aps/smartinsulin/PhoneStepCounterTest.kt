package app.aaps.plugins.aps.smartInsulin

import android.content.Context
import app.aaps.plugins.aps.smartInsulin.testutil.FakeAAPSLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Counting logic for the phone pedometer. The sensor reports a cumulative count since boot, so
 * everything here is about turning that into a rolling "steps in the last N minutes" — the thing
 * the watch path cannot answer, because it only ever reports a 5-minute block that has closed.
 */
class PhoneStepCounterTest {

    private val context: Context = mock()
    private lateinit var sut: PhoneStepCounter

    private val NOW = 1_000_000_000L
    private val MIN = 60_000L

    @BeforeEach fun setUp() {
        sut = PhoneStepCounter(context, FakeAAPSLogger(collect = false))
    }

    @Test fun `the first reading only sets a baseline`() {
        // The counter is cumulative since boot, so the first value is a total, not a delta —
        // banking it would credit every step taken since the phone last rebooted.
        sut.recordTotal(50_000L, NOW - MIN)
        assertEquals(0, sut.stepsInLast(5 * MIN, NOW))
    }

    @Test fun `deltas accumulate across readings`() {
        sut.recordTotal(50_000L, NOW - 4 * MIN)
        sut.recordTotal(50_200L, NOW - 3 * MIN)
        sut.recordTotal(50_500L, NOW - 1 * MIN)
        assertEquals(500, sut.stepsInLast(5 * MIN, NOW))
    }

    @Test fun `the window rolls, it does not step`() {
        // The point of the whole class: steps leave the window continuously as they age out,
        // rather than the count jumping when some fixed block boundary passes.
        sut.recordTotal(50_000L, NOW - 9 * MIN)
        sut.recordTotal(50_300L, NOW - 8 * MIN)   // ages out of a 5-min window
        sut.recordTotal(50_500L, NOW - 2 * MIN)
        assertEquals(200, sut.stepsInLast(5 * MIN, NOW))
        assertEquals(500, sut.stepsInLast(10 * MIN, NOW))
    }

    @Test fun `a reboot resets the baseline instead of counting backwards`() {
        sut.recordTotal(50_000L, NOW - 4 * MIN)
        sut.recordTotal(50_400L, NOW - 3 * MIN)
        sut.recordTotal(120L,    NOW - 2 * MIN)   // counter restarted at boot
        sut.recordTotal(320L,    NOW - 1 * MIN)
        // 400 from before the reboot, 200 after; the reboot reading itself contributes nothing.
        assertEquals(600, sut.stepsInLast(5 * MIN, NOW))
    }

    @Test fun `a repeated reading adds nothing`() {
        sut.recordTotal(50_000L, NOW - 3 * MIN)
        sut.recordTotal(50_250L, NOW - 2 * MIN)
        sut.recordTotal(50_250L, NOW - 1 * MIN)
        assertEquals(250, sut.stepsInLast(5 * MIN, NOW))
    }

    @Test fun `history older than the retention window is dropped`() {
        sut.recordTotal(50_000L, NOW - 90 * MIN)
        sut.recordTotal(50_900L, NOW - 80 * MIN)
        sut.recordTotal(51_000L, NOW - 1 * MIN)
        assertEquals(100, sut.stepsInLast(60 * MIN, NOW))
    }
}
