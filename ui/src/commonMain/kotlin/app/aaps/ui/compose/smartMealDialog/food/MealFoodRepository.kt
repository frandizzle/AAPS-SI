package app.aaps.ui.compose.smartMealDialog.food

import androidx.compose.ui.text.intl.Locale
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.withContext

/**
 * Meal estimates (Google Gemini) and barcode lookups (Open Food Facts) for the Smart Meal dialog.
 *
 * Ktor, so it runs on every target. Nothing here doses: the result only fills the dialog, and the user
 * confirms as for any other meal.
 */
@SingleIn(AppScope::class)
@Inject
class MealFoodRepository(
    private val preferences: Preferences,
    private val receiverStatusStore: ReceiverStatusStore
) {

    private val http = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            // A photo answer from a thinking model can take a while.
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
    private val gemini = GeminiFoodClient(http)
    private val openFoodFacts = OpenFoodFactsClient(http)

    /** Meal estimates are switched on in the settings. A key may still be missing. */
    val estimatesEnabled: Boolean get() = preferences.get(BooleanKey.ApsSmartInsulinMealAiEnabled)

    suspend fun estimate(description: String, photoJpeg: ByteArray?): FoodLookupResult {
        val key = preferences.get(StringKey.ApsSmartInsulinMealAiKey)
        if (!estimatesEnabled || key.isBlank()) return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_SET_UP)
        if (!receiverStatusStore.isConnected) return FoodLookupResult.Failure(FoodLookupResult.Reason.OFFLINE)
        val model = preferences.get(StringKey.ApsSmartInsulinMealAiModel).ifBlank { StringKey.ApsSmartInsulinMealAiModel.defaultValue }
        return withContext(aapsIoDispatcher) { gemini.estimate(key, model, description, photoJpeg, Locale.current.region) }
    }

    suspend fun lookupBarcode(barcode: String): FoodLookupResult {
        if (!receiverStatusStore.isConnected) return FoodLookupResult.Failure(FoodLookupResult.Reason.OFFLINE)
        return withContext(aapsIoDispatcher) { openFoodFacts.lookup(barcode) }
    }

    companion object {

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 60_000L
    }
}
