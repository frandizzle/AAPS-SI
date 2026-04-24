package app.aaps.core.interfaces.aps

import org.junit.Assert.assertEquals
import org.junit.Test

class RTTest {
    @Test
    fun `test fuelTrim serialization`() {
        val rt = RT(
            runningDynamicIsf = false,
            fuelTrim = 5.0
        )
        val json = rt.serialize()
        val deserialized = RT.deserialize(json)
        assertEquals(5.0, deserialized.fuelTrim!!, 0.001)
    }

    @Test
    fun `test fuelTrim null serialization`() {
        val rt = RT(
            runningDynamicIsf = false,
            fuelTrim = null
        )
        val json = rt.serialize()
        val deserialized = RT.deserialize(json)
        assertEquals(null, deserialized.fuelTrim)
    }
}
