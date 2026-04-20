package app.aaps.plugins.aps.smartInsulin
import app.aaps.core.interfaces.smartInsulin.MealMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.interfaces.profile.ProfileFunction

class ProfileLearnerTest {

    private val logger: AAPSLogger = mock()
    private val preferences: Preferences = mock()
    private val profileFunction: ProfileFunction = mock()
    private lateinit var learner: ProfileLearner

    @Before fun setUp() {
        whenever(preferences.get(any<StringKey>())).thenReturn("")
        learner = ProfileLearner(logger, preferences, profileFunction)
    }

    @Test fun `default insulin kinetics are correct`() {
        val i = learner.getInsulinKinetics()
        assertEquals(75.0,  i.peakMinutes, 0.001)
        assertEquals(360.0, i.diaMinutes,  0.001)
        assertEquals(0,     i.sampleCount)
    }

    @Test fun `observeInsulinKinetics moves values toward observed`() {
        val before = learner.getInsulinKinetics().peakMinutes
        learner.observeInsulinKinetics(50.0, 240.0, 0.15)
        val after = learner.getInsulinKinetics().peakMinutes
        
        assertTrue("Peak should decrease toward 50 from $before", after < before)
        assertTrue("Peak should not jump all the way to 50", after > 50.0)
    }

    @Test fun `observeCarbAbsorption updates correct mode`() {
        learner.observeCarbAbsorption(MealMode.BREAKFAST, 45.0, 180.0, 0.15)
        val c = learner.getCarbAbsorption(MealMode.BREAKFAST)
        
        assertEquals(1, c.sampleCount)
        assertTrue(c.absorptionMinutes > 120.0) // moved from default 180 toward something? Wait, default is 180.
    }

    @Test fun `carb profiles are independent`() {
        learner.observeCarbAbsorption(MealMode.BREAKFAST, 45.0, 120.0, 0.15)
        val breakfast = learner.getCarbAbsorption(MealMode.BREAKFAST)
        val lunch = learner.getCarbAbsorption(MealMode.LUNCH)
        
        assertEquals(1, breakfast.sampleCount)
        assertEquals(0, lunch.sampleCount)
    }

    @Test fun `resetProfiles clears all`() {
        learner.observeInsulinKinetics(60.0, 300.0, 0.15)
        learner.observeCarbAbsorption(MealMode.BREAKFAST, 45.0, 120.0, 0.15)
        
        learner.resetProfiles()
        
        assertEquals(0, learner.getInsulinKinetics().sampleCount)
        assertEquals(0, learner.getCarbAbsorption(MealMode.BREAKFAST).sampleCount)
    }
}
