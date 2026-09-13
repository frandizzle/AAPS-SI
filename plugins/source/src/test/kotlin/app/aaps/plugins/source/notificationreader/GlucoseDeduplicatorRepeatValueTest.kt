package app.aaps.plugins.source.notificationreader

import app.aaps.core.data.model.SourceSensor
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The repeat-value guard — this fork's one divergence from upstream's deduplicator.
 *
 * Upstream accepts anything past 0.8 x interval "regardless of value", which assumes a CGM app
 * posts once per reading. A G7 app keeps an ongoing notification and re-posts it on its own
 * schedule, and every re-post reads as a fresh reading to a purely time-based rule — so acceptance
 * locks onto a ~4-minute beat set by the threshold instead of the sensor's 5.
 *
 * Kept in its own file so the ported upstream test stays byte-comparable on a re-sync.
 */
class GlucoseDeduplicatorRepeatValueTest {

    private val min1 = 60_000L

    private class MemStore : GlucoseDeduplicator.StateStore {
        private var json: String? = null
        override fun load(): String? = json
        override fun save(json: String) { this.json = json }
    }

    private fun configWith(pkg: String, intervalMin: Int) =
        PackageConfig(
            version = 1,
            supportedPackages = setOf(pkg),
            packageToSensor = mapOf(pkg to SourceSensor.DEXCOM_G7_NATIVE),
            packageToIntervalMs = mapOf(pkg to intervalMin * 60_000L)
        )

    private fun dedup() = GlucoseDeduplicator(configWith("p", 5), MemStore())

    @Test
    fun `a re-post of the same value just past the old bar is rejected`() {
        // The exact shape of the bug: an ongoing notification re-posted at 4:05 carrying the
        // reading already stored. Upstream took it and restarted the window from there.
        val d = dedup()
        assertThat(d.process("p", 0L, 120)).isTrue()
        assertThat(d.process("p", 4 * min1 + 5_000L, 120)).isFalse()
    }

    @Test
    fun `the genuine reading at five minutes is still accepted`() {
        val d = dedup()
        assertThat(d.process("p", 0L, 120)).isTrue()
        assertThat(d.process("p", 4 * min1 + 5_000L, 120)).isFalse()  // re-post swallowed
        assertThat(d.process("p", 5 * min1, 121)).isTrue()            // real reading lands on time
    }

    @Test
    fun `flat glucose still gets through once the interval is nearly complete`() {
        // Identical values are legitimate when BG is flat — they just wait the extra 45s.
        val d = dedup()
        assertThat(d.process("p", 0L, 120)).isTrue()
        assertThat(d.process("p", 4 * min1 + 50_000L, 120)).isTrue()
    }

    @Test
    fun `a changed value is admitted at the original bar`() {
        // Movement is evidence of a new reading, so early arrivals keep upstream's tolerance.
        val d = dedup()
        assertThat(d.process("p", 0L, 120)).isTrue()
        assertThat(d.process("p", 4 * min1, 131)).isTrue()
    }

    @Test
    fun `omitting the value keeps upstream's time-only rule`() {
        val d = dedup()
        assertThat(d.process("p", 0L)).isTrue()
        assertThat(d.process("p", 4 * min1)).isTrue()
    }

    @Test
    fun `the guard survives a restart`() {
        val store = MemStore()
        GlucoseDeduplicator(configWith("p", 5), store).process("p", 0L, 120)
        // Fresh instance, same persisted state — the last value has to come back with it.
        assertThat(GlucoseDeduplicator(configWith("p", 5), store).process("p", 4 * min1 + 5_000L, 120)).isFalse()
    }
}
