package app.aaps.plugins.source

import app.aaps.core.keys.StringNonKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

class DexcomPluginTest : TestBaseWithProfile() {

    private lateinit var dexcomPlugin: DexcomPlugin

    @BeforeEach
    fun setup() {
        // Preferences.get is declared non-null and the real store honours that by returning the
        // key's defaultValue. A bare Mockito mock hands back null, so stub it rather than making
        // production code defend against a contract only the mock breaks.
        whenever(preferences.get(StringNonKey.DexcomEnabledSensorTypes)).thenReturn("")
        dexcomPlugin = DexcomPlugin(rh, aapsLogger, context, config, preferences)
    }

    @Test
    fun advancedFilteringSupported() {
        assertThat(dexcomPlugin.advancedFilteringSupported()).isTrue()
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        dexcomPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }
}
