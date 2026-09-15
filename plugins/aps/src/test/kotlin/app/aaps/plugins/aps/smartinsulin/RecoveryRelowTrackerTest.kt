package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryRelowTrackerTest {

    private val guard = 86.4 // 4.8 mmol
    private val t0 = 1_700_000_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    @Test
    fun `one reading above the guard then back below is noise`() {
        val t = RecoveryRelowTracker()
        t.recordAboveGuard(88.0, min(0))
        assertFalse(t.onDipBelowGuard(guard))
        assertEquals(0, t.relowCount)
    }

    @Test
    fun `two or three small readings above the guard are still noise`() {
        val t = RecoveryRelowTracker()
        t.recordAboveGuard(88.0, min(0)); t.recordAboveGuard(90.0, min(5)); t.recordAboveGuard(92.0, min(10))
        assertFalse(t.onDipBelowGuard(guard))
        assertEquals(0, t.relowCount)
    }

    @Test
    fun `fifteen minutes above the guard counts even if it never rose much`() {
        val t = RecoveryRelowTracker()
        listOf(0, 5, 10, 15).forEach { t.recordAboveGuard(90.0, min(it)) }
        assertTrue(t.onDipBelowGuard(guard))
        assertEquals(1, t.relowCount)
    }

    @Test
    fun `a decent spike counts even if brief`() {
        val t = RecoveryRelowTracker()
        t.recordAboveGuard(95.0, min(0)); t.recordAboveGuard(guard + 18.0, min(5))
        assertTrue(t.onDipBelowGuard(guard))
        assertEquals(1, t.relowCount)
    }

    @Test
    fun `a spike just short of the margin is noise`() {
        val t = RecoveryRelowTracker()
        t.recordAboveGuard(guard + 17.0, min(0))
        assertFalse(t.onDipBelowGuard(guard))
    }

    @Test
    fun `repeated loop runs on one reading do not look sustained`() {
        val t = RecoveryRelowTracker()
        repeat(5) { t.recordAboveGuard(90.0, min(0)) }
        assertFalse(t.onDipBelowGuard(guard))
    }

    @Test
    fun `a noise dip closes the excursion so the next one starts fresh`() {
        val t = RecoveryRelowTracker()
        listOf(0, 5, 10).forEach { t.recordAboveGuard(90.0, min(it)) }
        assertFalse(t.onDipBelowGuard(guard))
        // Back above at 20 — only 10 minutes of this excursion, so it's noise too, even though
        // BG was first above the guard 25 minutes ago.
        listOf(20, 25, 30).forEach { t.recordAboveGuard(90.0, min(it)) }
        assertFalse(t.onDipBelowGuard(guard))
        assertEquals(0, t.relowCount)
    }

    @Test
    fun `dip with no excursion recorded is not a relow`() {
        assertFalse(RecoveryRelowTracker().onDipBelowGuard(guard))
    }

    @Test
    fun `relows count up and cap`() {
        val t = RecoveryRelowTracker()
        repeat(4) {
            t.recordAboveGuard(guard + 30.0, min(it * 30))
            t.onDipBelowGuard(guard)
        }
        assertEquals(RecoveryRelowTracker.RELOW_MAX_EXTENSIONS, t.relowCount)
    }

    @Test
    fun `reset clears count and excursion`() {
        val t = RecoveryRelowTracker()
        t.recordAboveGuard(guard + 30.0, min(0)); t.onDipBelowGuard(guard)
        t.recordAboveGuard(guard + 30.0, min(10))
        t.reset()
        assertEquals(0, t.relowCount)
        assertFalse(t.onDipBelowGuard(guard))
    }

    @Test
    fun `window doubles on first relow and triples at the cap`() {
        assertEquals(25 * 60_000L, reboundWindowMs(25, 0, 0))
        assertEquals(50 * 60_000L, reboundWindowMs(25, 1, 0))
        assertEquals(75 * 60_000L, reboundWindowMs(25, 2, 0))
        assertEquals(75 * 60_000L, reboundWindowMs(25, 5, 0))
    }

    @Test
    fun `rollercoaster extension stacks on top of relow extension`() {
        assertEquals((50 + 15) * 60_000L, reboundWindowMs(25, 1, 1))
        assertEquals((50 + 45) * 60_000L, reboundWindowMs(25, 1, 9))
    }
}
