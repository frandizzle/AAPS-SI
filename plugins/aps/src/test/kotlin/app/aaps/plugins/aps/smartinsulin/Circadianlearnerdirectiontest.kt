package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Directional-invariant tests for CircadianLearner.
 *
 * These tests exist to lock in the multiplier direction convention documented
 * at the top of [CircadianLearner]. Recall:
 *
 *   dosingISF  = profileISF  / isfMult       (isfMult is a DIVISOR)
 *   finalBasal = profileBasal * basalMult
 *
 *   isfMult   UP   →  dosingISF DOWN  →  MORE insulin (more aggressive)
 *   isfMult   DOWN →  dosingISF UP    →  LESS insulin (less aggressive)
 *   basalMult UP   →  MORE continuous insulin
 *   basalMult DOWN →  LESS continuous insulin
 *
 * The historical bug was several penalty paths multiplying isfMult in the
 * direction that matched the *displayed* ISF value (where higher = less
 * insulin) rather than the *multiplier* (where higher = more insulin).
 * That made some penalties move insulin in the opposite direction of intent.
 *
 * Each test below asserts that a given outcome moves the multipliers in the
 * direction that actually corresponds to its stated intent. If the convention
 * gets flipped again in the future, these tests fail loudly.
 *
 * NOTE: Only paths reachable through the PUBLIC API are tested directly here.
 * The rollercoaster and sustained-below paths live inside private functions
 * and are not exercised by this file — they should be validated through the
 * integration test surface (driving CircadianLearner.update() with crafted
 * BG/IOB sequences). The fixes to those paths were audited manually against
 * this same convention.
 */
class CircadianLearnerDirectionTest {

    private lateinit var logger: AAPSLogger
    private lateinit var preferences: Preferences
    private lateinit var learner: CircadianLearner

    @BeforeEach
    fun setup() {
        logger      = mock()
        preferences = mock()
        // No persisted state — start from defaults (all multipliers = 1.0)
        whenever(preferences.get(any<StringKey>())).thenReturn("")
        learner = CircadianLearner(logger, preferences)
    }

    private fun freshLearner(): CircadianLearner {
        val l = mock<AAPSLogger>()
        val p = mock<Preferences>()
        whenever(p.get(any<StringKey>())).thenReturn("")
        return CircadianLearner(l, p)
    }

    // ── Single-outcome direction tests ────────────────────────────────────────

    @Test
    fun `MISSED outcome moves both mults UP (more insulin)`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "MISSED")

        val isfAfter = learner.isfMultiplier(hour, dow)
        val basAfter = learner.basalMultiplier(hour, dow)

        // isfMult UP → dosingISF DOWN → more insulin per BG gap (correct for "needed more")
        assertTrue(
            isfAfter > isfBefore,
            "MISSED must raise isfMult (more insulin). before=$isfBefore after=$isfAfter"
        )
        // basalMult UP → finalBasal UP → more continuous insulin
        assertTrue(
            basAfter > basBefore,
            "MISSED must raise basalMult (more insulin). before=$basBefore after=$basAfter"
        )
    }

    @Test
    fun `OVERSHOT outcome moves both mults DOWN (less insulin)`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "OVERSHOT")

        val isfAfter = learner.isfMultiplier(hour, dow)
        val basAfter = learner.basalMultiplier(hour, dow)

        // isfMult DOWN → dosingISF UP → less insulin per BG gap (correct for "too much")
        assertTrue(
            isfAfter < isfBefore,
            "OVERSHOT must lower isfMult (less insulin). before=$isfBefore after=$isfAfter"
        )
        assertTrue(
            basAfter < basBefore,
            "OVERSHOT must lower basalMult (less insulin). before=$basBefore after=$basAfter"
        )
    }

    @Test
    fun `SEVERE_LOW outcome moves both mults DOWN`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "SEVERE_LOW")

        val isfAfter = learner.isfMultiplier(hour, dow)
        val basAfter = learner.basalMultiplier(hour, dow)

        assertTrue(
            isfAfter < isfBefore,
            "SEVERE_LOW must lower isfMult. before=$isfBefore after=$isfAfter"
        )
        assertTrue(
            basAfter < basBefore,
            "SEVERE_LOW must lower basalMult. before=$basBefore after=$basAfter"
        )
    }

    @Test
    fun `SEVERE_LOW pulls isfMult DOWN harder than OVERSHOT`() {
        // OVERSHOT and SEVERE_LOW both pull DOWN, but SEVERE_LOW should pull harder.
        // We test on fresh learners so the starting state is identical (1.0).
        val hour = 9
        val dow  = 3

        val mildLearner   = freshLearner()
        val severeLearner = freshLearner()

        mildLearner.nudgeIsfFromPdpEpisode(hour, dow, "OVERSHOT")
        severeLearner.nudgeIsfFromPdpEpisode(hour, dow, "SEVERE_LOW")

        val mildIsf   = mildLearner.isfMultiplier(hour, dow)
        val severeIsf = severeLearner.isfMultiplier(hour, dow)

        assertTrue(
            severeIsf < mildIsf,
            "SEVERE_LOW should reduce isfMult more than OVERSHOT. mild=$mildIsf severe=$severeIsf"
        )
    }

    @Test
    fun `PERFECT outcome leaves both mults unchanged`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "PERFECT")

        assertEquals(isfBefore, learner.isfMultiplier(hour, dow), 1e-9, "PERFECT must hold isfMult")
        assertEquals(basBefore, learner.basalMultiplier(hour, dow), 1e-9, "PERFECT must hold basalMult")
    }

    @Test
    fun `GOOD outcome leaves both mults unchanged`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "GOOD")

        assertEquals(isfBefore, learner.isfMultiplier(hour, dow), 1e-9)
        assertEquals(basBefore, learner.basalMultiplier(hour, dow), 1e-9)
    }

    @Test
    fun `PARTIAL outcome leaves both mults unchanged`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "PARTIAL")

        assertEquals(isfBefore, learner.isfMultiplier(hour, dow), 1e-9)
        assertEquals(basBefore, learner.basalMultiplier(hour, dow), 1e-9)
    }

    @Test
    fun `MILD_OVER outcome moves both mults DOWN (sub-target landing, no guard hit)`() {
        val hour = 9
        val dow  = 3
        val isfBefore = learner.isfMultiplier(hour, dow)
        val basBefore = learner.basalMultiplier(hour, dow)

        learner.nudgeIsfFromPdpEpisode(hour, dow, "MILD_OVER")

        val isfAfter = learner.isfMultiplier(hour, dow)
        val basAfter = learner.basalMultiplier(hour, dow)

        assertTrue(
            isfAfter < isfBefore,
            "MILD_OVER must lower isfMult (still over-delivered even without guard breach). before=$isfBefore after=$isfAfter"
        )
        assertTrue(
            basAfter < basBefore,
            "MILD_OVER must lower basalMult. before=$basBefore after=$basAfter"
        )
    }

    @Test
    fun `MILD_OVER moves mults DOWN less than OVERSHOT (gentler — no guard tripped)`() {
        // Use two fresh learners to measure magnitudes from the same starting point.
        val mildLearner   = freshLearner()
        val severeLearner = freshLearner()
        val hour = 9
        val dow  = 3

        val mildIsfBefore = mildLearner.isfMultiplier(hour, dow)
        val ovIsfBefore   = severeLearner.isfMultiplier(hour, dow)

        mildLearner.nudgeIsfFromPdpEpisode(hour, dow, "MILD_OVER")
        severeLearner.nudgeIsfFromPdpEpisode(hour, dow, "OVERSHOT")

        val mildDelta = mildIsfBefore - mildLearner.isfMultiplier(hour, dow)
        val ovDelta   = ovIsfBefore   - severeLearner.isfMultiplier(hour, dow)

        assertTrue(
            mildDelta > 0.0 && ovDelta > 0.0,
            "both outcomes must reduce isfMult — mild=$mildDelta ov=$ovDelta"
        )
        assertTrue(
            mildDelta < ovDelta,
            "MILD_OVER must move LESS than OVERSHOT. mild=$mildDelta vs ov=$ovDelta"
        )
    }

    // ── Monotonicity tests — guards against arithmetic drift / EWMA interaction ──

    @Test
    fun `repeated OVERSHOT monotonically reduces both mults`() {
        val hour = 9
        val dow  = 3
        var prevIsf = learner.isfMultiplier(hour, dow)
        var prevBas = learner.basalMultiplier(hour, dow)

        repeat(5) { iter ->
            learner.nudgeIsfFromPdpEpisode(hour, dow, "OVERSHOT")
            val nowIsf = learner.isfMultiplier(hour, dow)
            val nowBas = learner.basalMultiplier(hour, dow)
            assertTrue(
                nowIsf <= prevIsf + 1e-9,
                "isfMult must monotonically decrease under repeated OVERSHOT (iter=$iter): $prevIsf → $nowIsf"
            )
            assertTrue(
                nowBas <= prevBas + 1e-9,
                "basalMult must monotonically decrease under repeated OVERSHOT (iter=$iter): $prevBas → $nowBas"
            )
            prevIsf = nowIsf
            prevBas = nowBas
        }
    }

    @Test
    fun `repeated MISSED monotonically raises both mults`() {
        val hour = 9
        val dow  = 3
        var prevIsf = learner.isfMultiplier(hour, dow)
        var prevBas = learner.basalMultiplier(hour, dow)

        repeat(5) { iter ->
            learner.nudgeIsfFromPdpEpisode(hour, dow, "MISSED")
            val nowIsf = learner.isfMultiplier(hour, dow)
            val nowBas = learner.basalMultiplier(hour, dow)
            assertTrue(
                nowIsf >= prevIsf - 1e-9,
                "isfMult must monotonically increase under repeated MISSED (iter=$iter): $prevIsf → $nowIsf"
            )
            assertTrue(
                nowBas >= prevBas - 1e-9,
                "basalMult must monotonically increase under repeated MISSED (iter=$iter): $prevBas → $nowBas"
            )
            prevIsf = nowIsf
            prevBas = nowBas
        }
    }

    // ── Clamp tests — verify the safety rails actually hold ──────────────────

    @Test
    fun `many repeated OVERSHOTs cannot drive isfMult below ISF_MULT_MIN`() {
        val hour = 9
        val dow  = 3
        // Drive 200 iterations — should fully saturate against the clamp
        repeat(200) { learner.nudgeIsfFromPdpEpisode(hour, dow, "OVERSHOT") }
        val isfFinal = learner.isfMultiplier(hour, dow)
        // ISF_MULT_MIN is 0.7 in CircadianLearner; assert we never go below it
        assertTrue(
            isfFinal >= 0.7 - 1e-9,
            "isfMult must clamp at >= 0.7 even after extreme repeated OVERSHOTs. final=$isfFinal"
        )
    }

    @Test
    fun `many repeated MISSEDs cannot drive isfMult above ISF_MULT_MAX`() {
        val hour = 9
        val dow  = 3
        repeat(200) { learner.nudgeIsfFromPdpEpisode(hour, dow, "MISSED") }
        val isfFinal = learner.isfMultiplier(hour, dow)
        // ISF_MULT_MAX is 1.5 in CircadianLearner
        assertTrue(
            isfFinal <= 1.5 + 1e-9,
            "isfMult must clamp at <= 1.5 even after extreme repeated MISSEDs. final=$isfFinal"
        )
    }
}