package app.aaps.core.interfaces.smartInsulin

/**
 * Starts and stops SmartInsulin activity sessions (golf, gym) from outside the SI tab, such as an
 * automation that runs when the user gets to the golf course.
 *
 * Lives in core/interfaces so plugins/automation can use it without a compile dependency on
 * plugins/aps. Implementation: SmartInsulinPlugin in plugins/aps.
 */
interface SmartInsulinSessions {

    /** The sessions a user can start. Names match the SI session labels. */
    enum class Kind { GOLF, GYM }

    /**
     * Starts a [kind] session. Does nothing when that session is already running, so a trigger that
     * fires twice does not stop it. A different session that is running is replaced.
     * @return false when SmartInsulin is not the active APS, so nothing was started
     */
    fun startSession(kind: Kind): Boolean

    /**
     * Stops the running session, if there is one.
     * @return false when SmartInsulin is not the active APS
     */
    fun stopSession(): Boolean
}
