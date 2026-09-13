/*
 * Ported from AndroidAPS dev (4.0) — nightscout/AndroidAPS, plugins/source/src/androidMain/
 * kotlin/app/aaps/plugins/source/notificationreader/NotificationCollectorService.kt
 *
 * Two adaptations for this branch, both marked inline: Metro DI -> Dagger Android, and the
 * glucose insert from a suspend call to the RxJava Single this branch's PersistenceLayer returns.
 * Everything else — notification text extraction, the RemoteViews fallback — is unchanged.
 *
 * AndroidAPS is licensed AGPL-3.0; this fork inherits that licence.
 */
package app.aaps.plugins.source.notificationreader

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.NotificationReaderPlugin
import dagger.android.AndroidInjection
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class NotificationCollectorService : NotificationListenerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var notificationReaderPlugin: NotificationReaderPlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var preferences: Preferences

    private var parser: NotificationParser? = null
    private var deduplicator: GlucoseDeduplicator? = null
    private val disposable = CompositeDisposable()

    override fun onCreate() {
        super.onCreate()
        // NotificationListenerService extends a framework base class, so it cannot extend
        // DaggerService. AndroidInjection.inject is the same thing DaggerService does in onCreate.
        AndroidInjection.inject(this)
        parser = NotificationParser(notificationReaderPlugin.packageConfig)
        deduplicator = GlucoseDeduplicator(
            packageConfig = notificationReaderPlugin.packageConfig,
            store = object : GlucoseDeduplicator.StateStore {
                override fun load(): String? =
                    preferences.get(StringNonKey.NotificationReaderDedupState).takeIf { it.isNotBlank() }

                override fun save(json: String) {
                    preferences.put(StringNonKey.NotificationReaderDedupState, json)
                }
            }
        )
        aapsLogger.debug(LTag.BGSOURCE, "NotificationCollectorService created")
    }

    override fun onDestroy() {
        disposable.clear()
        super.onDestroy()
        aapsLogger.debug(LTag.BGSOURCE, "NotificationCollectorService destroyed")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!notificationReaderPlugin.packageConfig.isSupportedPackage(packageName)) return
        if (!notificationReaderPlugin.isEnabled()) return
        // Remember it before the allow-list check, so a package the user has NOT ticked still
        // shows up as an option rather than being invisible until they tick something they
        // cannot see.
        notificationReaderPlugin.recordSeenPackage(packageName)
        if (!notificationReaderPlugin.isPackageEnabled(packageName)) {
            aapsLogger.debug(LTag.BGSOURCE, "Ignoring $packageName — not selected as a reading source")
            return
        }

        aapsLogger.debug(LTag.BGSOURCE, "Notification from: $packageName")
        processNotification(sbn.notification, packageName, readingTimeOf(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    /**
     * When the reading actually happened, as far as we can tell.
     *
     * [StatusBarNotification.postTime] is stamped by the system when the CGM app posts, so it is
     * the closest thing to the sensor's own timestamp that a notification carries — the broadcast
     * sources get the real one in their bundle, we do not.
     *
     * Upstream used System.currentTimeMillis() at processing time for both this and the dedup
     * window, which folds every source of delay — notification delivery, service wake-up, IO
     * scheduling — into the reading's timestamp. Two things went wrong with that. The stored BG
     * series drifted away from the sensor's real 5-minute grid, so deltas and loop cadence
     * followed arrival jitter rather than glucose. And the dedup gap was measured between
     * PROCESSING times against a threshold of 0.8 x interval — 4 min for a 5-min sensor — so a
     * reading posted on time but processed ~70s late measured as a 3:50 gap and was rejected
     * outright, leaving a 10-minute hole and putting the whole series a reading behind.
     *
     * Falls back to now only for a clock that cannot be trusted: a zero/absent post time, or one
     * in the future by more than [MAX_CLOCK_SKEW_MS]. An OLD post time is kept as-is and is not an
     * error — it is exactly what a reading posted while the phone was asleep looks like.
     */
    private fun readingTimeOf(sbn: StatusBarNotification): Long {
        val now = System.currentTimeMillis()
        val posted = sbn.postTime
        // Diagnostic only — nothing below acts on `when`. Apps are supposed to set it to the time
        // the event happened rather than the time it was posted, which would be a better reading
        // time than postTime if this app populates it. Logged so that can be settled from data
        // instead of assumed; changing the source and the dedup rule at once would make it
        // impossible to tell which one mattered.
        val whenMs = sbn.notification?.`when` ?: 0L
        aapsLogger.debug(
            LTag.BGSOURCE,
            "Times for ${sbn.packageName}: when=${if (whenMs > 0) "${(now - whenMs) / 1000}s ago" else "unset"} " +
                "postTime=${(now - posted) / 1000}s ago"
        )
        return if (posted <= 0L || posted > now + MAX_CLOCK_SKEW_MS) now else posted
    }

    private fun processNotification(notification: Notification?, packageName: String, readingTime: Long) {
        if (notification == null) return

        val texts = extractTexts(notification)
        if (texts.isEmpty()) {
            aapsLogger.debug(LTag.BGSOURCE, "No text in notification from $packageName")
            return
        }

        aapsLogger.debug(LTag.BGSOURCE, "Extracted texts: $texts")

        val useMgdl = profileFunction.getUnits() == GlucoseUnit.MGDL
        val result = parser?.extractGlucose(texts, packageName, useMgdl) ?: return

        aapsLogger.debug(LTag.BGSOURCE, "Glucose: ${result.glucoseMgdl} mg/dL from $packageName (${result.sourceSensor})")

        val delayMs = System.currentTimeMillis() - readingTime
        if (deduplicator?.process(packageName, readingTime, result.glucoseMgdl) != true) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "Skipping duplicate notification from $packageName " +
                    "(posted ${delayMs / 1000}s ago, current interval ${(deduplicator?.currentIntervalMs(packageName) ?: 0L) / 60_000}min)"
            )
            return
        }
        if (delayMs > LATE_DELIVERY_WARN_MS) {
            // Not fatal — the reading is stamped with its post time, so the series stays straight.
            // Worth seeing though: a consistently late listener means the loop runs late too.
            aapsLogger.debug(LTag.BGSOURCE, "Notification from $packageName handled ${delayMs / 1000}s after it was posted")
        }

        val gv = GV(
            timestamp = readingTime,
            value = result.glucoseMgdl.toDouble(),
            raw = null,
            noise = null,
            trendArrow = TrendArrow.NONE,
            sourceSensor = result.sourceSensor
        )

        // Upstream 4.0 awaits a suspend insert inside a coroutine. On this branch
        // insertCgmSourceData returns a Single, so the subscription is the equivalent — and it is
        // tracked so an insert in flight cannot outlive the service.
        disposable += persistenceLayer.insertCgmSourceData(Sources.NotificationReader, listOf(gv), emptyList(), null)
            .subscribe(
                { aapsLogger.debug(LTag.BGSOURCE, "Inserted ${result.glucoseMgdl} mg/dL from $packageName at $readingTime") },
                { e -> aapsLogger.error(LTag.BGSOURCE, "Error inserting glucose data", e) }
            )
    }

    /**
     * Extract visible text from notification.
     * Tries standard extras first (modern apps), falls back to RemoteViews inflation (legacy).
     */
    private fun extractTexts(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()

        // Modern notifications: extract from standard extras
        val texts = buildList {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
        }
        if (texts.isNotEmpty()) return texts

        // Legacy: inflate custom RemoteViews
        @Suppress("DEPRECATION")
        notification.contentView?.let { remoteViews ->
            try {
                val applied = remoteViews.apply(this, null)
                val root = applied.rootView as? ViewGroup ?: return@let
                val inflated = root.collectVisibleText()
                if (inflated.isNotEmpty()) return inflated
            } catch (e: Exception) {
                aapsLogger.debug(LTag.BGSOURCE, "RemoteViews inflation failed: ${e.message}")
            }
        }

        return emptyList()
    }
}

private const val MAX_CLOCK_SKEW_MS = 60_000L
private const val LATE_DELIVERY_WARN_MS = 30_000L

/**
 * Recursively collect text from all visible TextViews in a ViewGroup hierarchy.
 */
private fun ViewGroup.collectVisibleText(): List<String> = buildList {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        when (child) {
            is TextView -> child.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            is ViewGroup -> addAll(child.collectVisibleText())
        }
    }
}
