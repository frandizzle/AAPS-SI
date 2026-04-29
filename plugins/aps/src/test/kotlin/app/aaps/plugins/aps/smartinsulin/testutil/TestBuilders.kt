package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

object TestBuilders {
    fun glucoseStatus(glucose: Double, date: Long) = mock<GlucoseStatus>().apply {
        whenever(this.glucose).thenReturn(glucose)
        whenever(this.date).thenReturn(date)
        whenever(this.delta).thenReturn(0.0)
        whenever(this.shortAvgDelta).thenReturn(0.0)
        whenever(this.longAvgDelta).thenReturn(0.0)
        whenever(this.noise).thenReturn(0.0)
    }

    fun iobArray(iob: Double, activity: Double): Array<IobTotal> {
        return arrayOf(IobTotal(time = 0, iob = iob, activity = activity))
    }
}
