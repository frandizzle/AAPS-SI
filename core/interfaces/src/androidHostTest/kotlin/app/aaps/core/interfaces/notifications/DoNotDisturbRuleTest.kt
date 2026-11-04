package app.aaps.core.interfaces.notifications

import android.app.NotificationManager
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * The "off" half of "Override Do Not Disturb mode": no sound while Do Not Disturb is on, whatever the profile
 * allows. With the override on the alarm sounds as usual; the profile decides whether the alarm stream is heard.
 */
class DoNotDisturbRuleTest {

    private val dndOnFilters = listOf(
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE
    )

    @Test
    fun `the eat reminder follows the ringer, and no other alarm does`() {
        assertThat(DoNotDisturbRule.eatReminderStaysQuiet(AlarmSound.EAT_REMINDER, AudioManager.RINGER_MODE_NORMAL)).isFalse()
        assertThat(DoNotDisturbRule.eatReminderStaysQuiet(AlarmSound.EAT_REMINDER, AudioManager.RINGER_MODE_SILENT)).isTrue()
        assertThat(DoNotDisturbRule.eatReminderStaysQuiet(AlarmSound.EAT_REMINDER, AudioManager.RINGER_MODE_VIBRATE)).isTrue()
        AlarmSound.entries.filter { it != AlarmSound.EAT_REMINDER }.forEach {
            assertWithMessage("sound $it").that(DoNotDisturbRule.eatReminderStaysQuiet(it, AudioManager.RINGER_MODE_SILENT)).isFalse()
        }
        assertThat(DoNotDisturbRule.eatReminderStaysQuiet(null, AudioManager.RINGER_MODE_SILENT)).isFalse()
    }

    @Test
    fun `with Do Not Disturb off the alarm plays, whatever the setting`() {
        assertThat(DoNotDisturbRule.staysQuiet(overrideDnd = false, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL)).isFalse()
        assertThat(DoNotDisturbRule.staysQuiet(overrideDnd = true, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL)).isFalse()
    }

    @Test
    fun `with the override off the alarm stays quiet in every Do Not Disturb mode`() {
        dndOnFilters.forEach {
            assertWithMessage("interruption filter $it").that(DoNotDisturbRule.staysQuiet(overrideDnd = false, interruptionFilter = it)).isTrue()
        }
    }

    @Test
    fun `with the override on the alarm plays while Do Not Disturb is on`() {
        dndOnFilters.forEach {
            assertWithMessage("interruption filter $it").that(DoNotDisturbRule.staysQuiet(overrideDnd = true, interruptionFilter = it)).isFalse()
        }
    }

    @Test
    fun `an unknown Do Not Disturb state counts as off, so the alarm plays`() {
        assertThat(DoNotDisturbRule.staysQuiet(overrideDnd = false, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN)).isFalse()
    }
}
