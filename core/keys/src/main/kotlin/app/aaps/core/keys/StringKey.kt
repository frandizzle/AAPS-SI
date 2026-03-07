package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class StringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {

    GeneralUnits("units", "mg/dl"),
    GeneralLanguage("language", "default", defaultedBySM = true),
    GeneralPatientName("patient_name", ""),
    GeneralSkin("skin", ""),
    GeneralDarkMode("use_dark_mode", "dark", defaultedBySM = true),

    AapsDirectoryUri("aaps_directory", ""),

    ProtectionMasterPassword("master_password", "", isPassword = true),
    ProtectionSettingsPassword("settings_password", "", isPassword = true),
    ProtectionSettingsPin("settings_pin", "", isPin = true),
    ProtectionApplicationPassword("application_password", "", isPassword = true),
    ProtectionApplicationPin("application_pin", "", isPin = true),
    ProtectionBolusPassword("bolus_password", "", isPassword = true),
    ProtectionBolusPin("bolus_pin", "", isPin = true),

    OverviewCopySettingsFromNs(key = "statuslights_copy_ns", "", dependency = BooleanKey.OverviewShowStatusLights),

    SafetyAge("age", "adult"),
    MaintenanceEmail("maintenance_logs_email", "logs@aaps.app", defaultedBySM = true),
    MaintenanceIdentification("email_for_crash_report", ""),
    AutomationLocation("location", "PASSIVE", hideParentScreenIfHidden = true),

    SmsAllowedNumbers("smscommunicator_allowednumbers", ""),
    SmsOtpPassword("smscommunicator_otp_password", "", dependency = BooleanKey.SmsAllowRemoteCommands, isPassword = true),

    VirtualPumpType("virtualpump_type", "Generic AAPS"),

    NsClientUrl("nsclientinternal_url", ""),
    NsClientApiSecret("nsclientinternal_api_secret", "", isPassword = true),
    NsClientWifiSsids("ns_wifi_ssids", "", dependency = BooleanKey.NsClientUseWifi),
    NsClientAccessToken("nsclient_token", "", isPassword = true),

    // Google Drive settings
    GoogleDriveStorageType("google_drive_storage_type", "local"),
    GoogleDriveFolderId("google_drive_folder_id", ""),
    GoogleDriveRefreshToken("google_drive_refresh_token", "", isPassword = true),

    PumpCommonBolusStorage("pump_sync_storage_bolus", ""),
    PumpCommonTbrStorage("pump_sync_storage_tbr", ""),

    ApsSmartInsulinProfileFasting(
        "si_profile_fasting",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinProfileLowCarb(
        "si_profile_low_carb",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinProfileBreakfast(
        "si_profile_breakfast",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinProfileLunch(
        "si_profile_lunch",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinProfileDinner(
        "si_profile_dinner",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinProfileExtended(
        "si_profile_extended",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinAggressionState(
        "si_aggression_state",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
    ApsSmartInsulinTrackerState(
        "si_tracker_state",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = false
    ),
    ApsSmartInsulinBasalState(
        "si_basal_state",
        defaultValue          = "",
        showInApsMode         = false,
        showInNsClientMode    = false,
        showInPumpControlMode = false,
        exportable            = true
    ),
}