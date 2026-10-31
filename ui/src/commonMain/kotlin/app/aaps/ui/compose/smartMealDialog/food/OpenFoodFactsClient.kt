package app.aaps.ui.compose.smartMealDialog.food

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Looks up a scanned barcode in Open Food Facts, a free and open food database. Only the barcode is
 * sent. No key is needed; the database asks for an app name in the User-Agent.
 *
 * Carbohydrate is taken as printed per 100 g. Australian, New Zealand and European labels print it
 * without fibre, which is what the meal planner wants. US labels include fibre, so for a product only
 * sold in the US the fibre is taken off.
 */
internal class OpenFoodFactsClient(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(barcode: String): FoodLookupResult {
        val code = barcode.filter { it.isDigit() }
        if (code.length !in 8..14) return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, barcode)
        return try {
            val response = http.get(URL.replace("{code}", code)) {
                header("User-Agent", USER_AGENT)
                parameter("fields", "product_name,brands,nutriments,serving_size,serving_quantity,countries_tags")
            }
            when (response.status.value) {
                200  -> parse(response.bodyAsText(), code)
                404  -> FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, code)
                429  -> FoodLookupResult.Failure(FoodLookupResult.Reason.LIMIT_REACHED)
                else -> FoodLookupResult.Failure(FoodLookupResult.Reason.OTHER, response.status.value.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FoodLookupResult.failureOf(e)
        }
    }

    internal fun parse(body: String, code: String): FoodLookupResult {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.BAD_RESPONSE)
        val product = root["product"] as? JsonObject
        if ((root["status"] as? JsonPrimitive)?.intOrNull != 1 || product == null)
            return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, code)
        val n = product["nutriments"] as? JsonObject ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, code)
        val carbsLabel = n.num("carbohydrates_100g") ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, code)
        val fibre = n.num("fiber_100g") ?: 0.0
        // A product also sold here most likely has the local label behind its numbers.
        val countries = product["countries_tags"]?.toString().orEmpty()
        val usLabel = countries.contains("united-states") && !countries.contains("australia") && !countries.contains("new-zealand")
        val carbs = if (usLabel) (carbsLabel - fibre).coerceAtLeast(0.0) else carbsLabel
        val serving = product.num("serving_quantity")?.takeIf { it > 0.0 && it <= MAX_SERVING_G }
        val name = listOfNotNull(product.str("brands")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() },
                                 product.str("product_name")?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ").ifEmpty { code }
        return FoodLookupResult.Success(
            listOf(
                FoodItem(
                    name = name,
                    baseGrams = serving ?: 100.0,
                    portion = if (serving != null) product.str("serving_size")?.trim().orEmpty() else "",
                    carbsPer100 = carbs.coerceIn(0.0, 100.0),
                    fibrePer100 = fibre.coerceIn(0.0, 100.0),
                    proteinPer100 = (n.num("proteins_100g") ?: 0.0).coerceIn(0.0, 100.0),
                    fatPer100 = (n.num("fat_100g") ?: 0.0).coerceIn(0.0, 100.0),
                    source = FoodSource.BARCODE
                )
            )
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    companion object {

        const val URL = "https://world.openfoodfacts.org/api/v2/product/{code}.json"
        const val USER_AGENT = "AAPS-SmartInsulin/1.0 (personal use)"
        const val MAX_SERVING_G = 2000.0
    }
}
