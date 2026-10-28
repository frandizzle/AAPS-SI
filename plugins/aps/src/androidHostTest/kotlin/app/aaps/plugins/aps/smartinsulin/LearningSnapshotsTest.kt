package app.aaps.plugins.aps.smartInsulin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [LearningSnapshots]: saved copies of the learning, so learning from a bad site can be undone. */
class LearningSnapshotsTest {

    /** A learner that only holds a value. */
    private class Fake(override val undoName: String, var value: String) : UndoableLearner {

        var imports = 0
        override fun exportState() = value
        override fun importState(state: String) {
            value = state
            imports++
        }
    }

    private var file: String? = null
    private val a = Fake("a", "a0")
    private val b = Fake("b", "b0")
    private val learners = listOf(a, b)

    private fun snapshots() = LearningSnapshots({ file }, { file = it })

    @Test
    fun `a copy at the first cycle, then every 4 h`() {
        val s = snapshots()
        s.onCycle(T0, POD1, learners)
        s.onCycle(T0 + 3 * HOUR, POD1, learners)
        assertEquals(1, s.list().size)
        s.onCycle(T0 + 4 * HOUR, POD1, learners)
        assertEquals(2, s.list().size)
    }

    @Test
    fun `a pod change takes a copy at once`() {
        val s = snapshots()
        s.onCycle(T0, POD1, learners)
        s.onCycle(T0 + HOUR, POD2, learners)
        assertEquals(listOf(POD1, POD2), s.list().map { it.podMs })
    }

    @Test
    fun `only the current pod and the one before are kept`() {
        val s = snapshots()
        s.onCycle(T0, POD1, learners)
        s.onCycle(T0 + 4 * HOUR, POD1, learners)
        s.onCycle(T0 + 8 * HOUR, POD2, learners)
        s.onCycle(T0 + 12 * HOUR, POD3, learners)
        assertEquals(listOf(POD2, POD3), s.list().map { it.podMs })
    }

    @Test
    fun `restore puts every learner back and deletes the newer copies`() {
        val s = snapshots()
        s.onCycle(T0, POD1, learners)
        a.value = "a1"; b.value = "b1"
        s.onCycle(T0 + 4 * HOUR, POD1, learners)
        a.value = "a2"; b.value = "b2"
        s.onCycle(T0 + 8 * HOUR, POD1, learners)
        a.value = "a3"

        assertTrue(s.restore(T0 + 4 * HOUR, learners))
        assertEquals("a1", a.value)
        assertEquals("b1", b.value)
        assertEquals(listOf(T0, T0 + 4 * HOUR), s.list().map { it.timeMs })
        // The next copy is 4 h after the last one kept, so it is taken at once.
        s.onCycle(T0 + 9 * HOUR, POD1, learners)
        assertEquals(3, s.list().size)
    }

    @Test
    fun `a learner the copy does not know is left as it is`() {
        val s = snapshots()
        s.onCycle(T0, POD1, listOf(a))
        b.value = "b9"
        s.restore(T0, learners)
        assertEquals("b9", b.value)
        assertEquals(0, b.imports)
    }

    @Test
    fun `an unknown time changes nothing`() {
        val s = snapshots()
        s.onCycle(T0, POD1, learners)
        a.value = "a5"
        assertFalse(s.restore(T0 + 1, learners))
        assertEquals("a5", a.value)
    }

    @Test
    fun `copies survive a restart`() {
        snapshots().onCycle(T0, POD1, learners)
        a.value = "a7"
        val again = snapshots()
        assertEquals(1, again.list().size)
        again.restore(T0, learners)
        assertEquals("a0", a.value)
    }

    @Test
    fun `clear deletes every copy`() {
        val s = snapshots()
        s.onCycle(T0, POD1, learners)
        s.clear()
        assertTrue(s.list().isEmpty())
        assertTrue(snapshots().list().isEmpty())
    }

    @Test
    fun `a broken file starts empty`() {
        file = "not json"
        assertTrue(snapshots().list().isEmpty())
    }

    private companion object {

        const val HOUR = 3_600_000L
        const val T0 = 1_790_000_000_000L
        const val POD1 = T0 - 10 * HOUR
        const val POD2 = T0 + HOUR
        const val POD3 = T0 + 12 * HOUR
    }
}
