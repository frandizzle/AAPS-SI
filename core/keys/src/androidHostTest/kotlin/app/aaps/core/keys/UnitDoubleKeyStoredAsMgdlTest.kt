package app.aaps.core.keys

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * SmartInsulin stores its unit values in raw mg/dl, many of them under the 36 mg/dl line where the
 * magnitude guess switches to mmol. Pin which keys opt out of the guess so it is not lost in a merge.
 */
class UnitDoubleKeyStoredAsMgdlTest {

    @Test
    fun smartInsulinKeys_areStoredAsMgdl() {
        val si = UnitDoubleKey.entries.filter { it.name.startsWith("ApsSmartInsulin") }
        assertThat(si).isNotEmpty()
        assertThat(si.filterNot { it.storedAsMgdl }).isEmpty()
        assertThat(UnitDoubleKey.ApsSmartInsulinActivityLightTarget.storedAsMgdl).isTrue()
        assertThat(UnitDoubleKey.ApsSmartInsulinLunchIsf.storedAsMgdl).isTrue()
    }

    @Test
    fun upstreamKeys_keepTheMagnitudeGuess() {
        val upstream = UnitDoubleKey.entries.filterNot { it.name.startsWith("ApsSmartInsulin") }
        assertThat(upstream.filter { it.storedAsMgdl }).isEmpty()
    }
}
