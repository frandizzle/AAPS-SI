package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/** Silent logger for tests. Optionally collects debug messages for assertion. */
class FakeAAPSLogger(private val collect: Boolean = false) : AAPSLogger {
    val debugMessages = mutableListOf<String>()

    override fun debug(message: String)                                                               { if (collect) debugMessages.add(message) }
    override fun debug(enable: Boolean, tag: LTag, message: String)                                  { if (collect && enable) debugMessages.add(message) }
    override fun debug(tag: LTag, message: String)                                                    { if (collect) debugMessages.add(message) }
    override fun debug(tag: LTag, accessor: () -> String)                                             { if (collect) debugMessages.add(accessor()) }
    override fun debug(tag: LTag, format: String, vararg arguments: Any?)                             { if (collect) debugMessages.add(format) }
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) { if (collect) debugMessages.add(message) }

    override fun info(tag: LTag, message: String)                                                     {}
    override fun info(tag: LTag, format: String, vararg arguments: Any?)                              {}
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}

    override fun warn(tag: LTag, message: String)                                                     {}
    override fun warn(tag: LTag, format: String, vararg arguments: Any?)                              {}
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}

    override fun error(tag: LTag, message: String)                                                    {}
    override fun error(tag: LTag, message: String, throwable: Throwable)                              {}
    override fun error(tag: LTag, format: String, vararg arguments: Any?)                             {}
    override fun error(message: String)                                                               {}
    override fun error(message: String, throwable: Throwable)                                         {}
    override fun error(format: String, vararg arguments: Any?)                                        {}
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
}