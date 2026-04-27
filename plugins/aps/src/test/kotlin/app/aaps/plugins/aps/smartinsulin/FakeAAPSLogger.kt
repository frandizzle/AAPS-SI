package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/** Silent logger for tests. Optionally collect messages for assertion. */
class FakeAAPSLogger(private val collect: Boolean = false) : AAPSLogger {
    val debugMessages = mutableListOf<String>()

    override fun debug(tag: LTag, message: String) {
        if (collect) debugMessages.add(message)
    }
    override fun debug(message: String)                          {}
    override fun info(tag: LTag, message: String)                {}
    override fun warn(tag: LTag, message: String)                {}
    override fun error(tag: LTag, message: String)               {}
    override fun error(tag: LTag, message: String, e: Throwable) {}
    override fun error(message: String)                          {}
}
