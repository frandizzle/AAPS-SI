package app.aaps.plugins.source

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.notificationreader.NotificationCollectorService
import app.aaps.plugins.source.notificationreader.PackageConfig
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads glucose values from the notifications posted by official CGM apps.
 *
 * Ported from AndroidAPS dev (4.0), where the plugin shell is written against Metro DI, Compose
 * and TextRef — none of which exist on this branch — so this file is a rewrite against the local
 * [AbstractBgSourcePlugin] contract rather than a copy. The logic it drives (parser, package
 * config, deduplicator, collector service) is upstream's, near-verbatim.
 *
 * The point of it for a G7: Dexcom's G7 app publishes no broadcast for AAPS to receive, so the
 * only native route is scraping the notification it posts every reading. That is inherently
 * weaker than a broadcast — the user has to grant notification access, the CGM app has to keep
 * posting, and Android may coalesce notifications — but it removes the need for xDrip or
 * Juggluco as a bridge.
 *
 * Package definitions live in an asset and are refreshed from upstream's `versions` branch on
 * start, so a CGM app changing its notification format can be handled without an app release.
 *
 * AndroidAPS is licensed AGPL-3.0; this fork inherits that licence.
 */
@Singleton
class NotificationReaderPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    private val context: Context
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_dexcom_g6)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.notification_reader)
        .shortName(R.string.notification_reader_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_notification_reader),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences
), BgSource {

    @Volatile
    var packageConfig: PackageConfig = PackageConfig(0, emptySet(), emptyMap(), emptyMap())
        private set

    override fun onStart() {
        super.onStart()
        packageConfig = loadPackageConfig()
        // Off the main thread — this reaches the network. Upstream spawns a bare Thread here too;
        // a failure is non-fatal, the bundled asset stays in force.
        Thread { updateDefinitionsFromRemote() }.start()
    }

    /**
     * True when the user has granted this app notification access, which is what lets
     * [NotificationCollectorService] see anything at all. There is no runtime-permission dialog
     * for it — it is a Settings toggle — hence the preference below rather than a request.
     */
    fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        super.addPreferenceScreen(preferenceManager, parent, context, requiredKey)
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "notification_reader_settings"
            title = rh.gs(R.string.notification_reader)
            initialExpandedChildrenCount = 0
            addPreference(Preference(context).apply {
                title = rh.gs(R.string.notification_reader_access_title)
                summary = rh.gs(
                    if (notificationAccessGranted()) R.string.notification_reader_access_granted
                    else R.string.notification_reader_access_missing
                )
                setOnPreferenceClickListener {
                    // No runtime permission exists for notification access; the only route is the
                    // system settings screen, so send the user straight there.
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }
            })
        }
    }

    private fun loadPackageConfig(): PackageConfig =
        try {
            val stored = preferences.get(StringNonKey.NotificationReaderPackages)
            val json = if (stored.isNotEmpty()) stored
            else context.assets.open(PACKAGE_CONFIG_ASSET).bufferedReader().use { it.readText() }
                .also { preferences.put(StringNonKey.NotificationReaderPackages, it) }
            PackageConfig.fromJson(json)
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Failed to load package config", e)
            PackageConfig(0, emptySet(), emptyMap(), emptyMap())
        }

    private fun updateDefinitionsFromRemote() {
        try {
            val remoteJson = URL(REMOTE_DEFINITIONS_URL).readText()
            val remoteConfig = PackageConfig.fromJson(remoteJson)
            if (remoteConfig.version > packageConfig.version) {
                aapsLogger.info(LTag.BGSOURCE, "Updating notification reader definitions: v${packageConfig.version} → v${remoteConfig.version}")
                preferences.put(StringNonKey.NotificationReaderPackages, remoteJson)
                packageConfig = remoteConfig
            } else {
                aapsLogger.debug(LTag.BGSOURCE, "Notification reader definitions up to date (v${packageConfig.version})")
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.BGSOURCE, "Failed to fetch remote definitions: ${e.message}")
        }
    }

    companion object {

        private const val PACKAGE_CONFIG_ASSET = "notification_reader_packages.json"
        private const val REMOTE_DEFINITIONS_URL =
            "https://raw.githubusercontent.com/nightscout/AndroidAPS/refs/heads/versions/notification_reader_packages.json"
    }
}
