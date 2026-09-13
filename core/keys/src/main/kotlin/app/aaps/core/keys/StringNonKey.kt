package app.aaps.core.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class StringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    QuickWizard(key = "QuickWizard", defaultValue = "[]"),
    WearCwfWatchfaceName(key = "wear_cwf_watchface_name", defaultValue = ""),
    WearCwfAuthorVersion(key = "wear_cwf_author_version", defaultValue = ""),
    WearCwfFileName(key = "wear_cwf_filename", defaultValue = ""),
    BolusInfoStorage(key = "key_bolus_storage", defaultValue = ""),
    ActivePumpType(key = "active_pump_type", defaultValue = ""),
    ActivePumpSerialNumber(key = "active_pump_serial_number", defaultValue = ""),
    SmsOtpSecret("smscommunicator_otp_secret", defaultValue = ""),
    TotalBaseBasal("TBB", defaultValue = "10.00"),

    /** Cached notification_reader_packages.json — the bundled asset on first run, then whatever
     *  the remote definitions refresh last fetched. */
    NotificationReaderPackages(key = "notification_reader_packages", defaultValue = ""),

    /** Per-package dedup state for the notification reader (last accepted timestamp and learned
     *  interval). Survives restarts so a reboot cannot re-admit a reading already stored. */
    NotificationReaderDedupState(key = "notification_reader_dedup_state", defaultValue = "")
}
