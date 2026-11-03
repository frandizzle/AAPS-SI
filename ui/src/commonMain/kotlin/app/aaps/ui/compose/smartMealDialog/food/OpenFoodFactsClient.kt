package app.aaps.ui.compose.smartMealDialog.food

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Looks up a scanned barcode in Open Food Facts, a free and open food database, or searches it for products by name.
 * Only the barcode or the words searched for are sent. No key is needed; the database asks for an app name in the
 * User-Agent. A search is limited to 10 a minute, so it runs when asked, never as the user types.
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

    /** Products sold in Australia whose name or brand matches [query], best match first. */
    suspend fun search(query: String): FoodLookupResult {
        val words = query.replace(Regex("[\"():]"), " ").trim()
        if (words.isEmpty()) return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, query)
        return try {
            val response = http.get(SEARCH_URL) {
                header("User-Agent", USER_AGENT)
                parameter("q", "$words $SEARCH_COUNTRY")
                parameter("page_size", SEARCH_SIZE)
                parameter("fields", "code,product_name,brands,nutriments,serving_size,serving_quantity,countries_tags")
            }
            when (response.status.value) {
                200       -> parseSearch(response.bodyAsText(), query)
                429       -> FoodLookupResult.Failure(FoodLookupResult.Reason.LIMIT_REACHED)
                502, 503  -> FoodLookupResult.Failure(FoodLookupResult.Reason.BUSY)
                else      -> FoodLookupResult.Failure(FoodLookupResult.Reason.OTHER, response.status.value.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FoodLookupResult.failureOf(e)
        }
    }

    internal fun parseSearch(body: String, query: String): FoodLookupResult {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.BAD_RESPONSE)
        val hits = root["hits"] as? JsonArray ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.BAD_RESPONSE)
        // Products without nutrition values cannot be planned with, so they are left out.
        val items = hits.mapNotNull { hit -> (hit as? JsonObject)?.let { item(it, it.str("code").orEmpty(), FoodSource.SEARCH) } }
        return if (items.isEmpty()) FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, query) else FoodLookupResult.Success(items)
    }

    internal fun parse(body: String, code: String): FoodLookupResult {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.BAD_RESPONSE)
        val product = root["product"] as? JsonObject
        if ((root["status"] as? JsonPrimitive)?.intOrNull != 1 || product == null)
            return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, code)
        val item = item(product, code, FoodSource.BARCODE) ?: return FoodLookupResult.Failure(FoodLookupResult.Reason.NOT_FOUND, code)
        return FoodLookupResult.Success(listOf(item))
    }

    /** One serving of [product], or 100 g when it gives no serving size. Null without carbs per 100 g. */
    private fun item(product: JsonObject, code: String, source: FoodSource): FoodItem? {
        val n = product["nutriments"] as? JsonObject ?: return null
        val carbsLabel = n.num("carbohydrates_100g") ?: return null
        val fibre = n.num("fiber_100g") ?: 0.0
        // A product also sold here most likely has the local label behind its numbers.
        val countries = product["countries_tags"]?.toString().orEmpty()
        val usLabel = countries.contains("united-states") && !countries.contains("australia") && !countries.contains("new-zealand")
        val carbs = if (usLabel) (carbsLabel - fibre).coerceAtLeast(0.0) else carbsLabel
        val serving = product.num("serving_quantity")?.takeIf { it > 0.0 && it <= MAX_SERVING_G }
        // The barcode lookup gives the brands as one text, the search as a list.
        val brand = ((product["brands"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: product.str("brands")
        val name = listOfNotNull(brand?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() },
                                 product.str("product_name")?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ").ifEmpty { code }
        val protein = (n.num("proteins_100g") ?: 0.0).coerceIn(0.0, 100.0)
        val fat = (n.num("fat_100g") ?: 0.0).coerceIn(0.0, 100.0)
        return FoodItem(
            name = name,
            baseGrams = serving ?: 100.0,
            portion = if (serving != null) product.str("serving_size")?.trim().orEmpty() else "",
            carbsPer100 = carbs.coerceIn(0.0, 100.0),
            fibrePer100 = fibre.coerceIn(0.0, 100.0),
            proteinPer100 = protein,
            fatPer100 = fat,
            source = source,
            speed = FoodSpeed.guess(carbs, n.num("sugars_100g"), fibre, protein, fat)
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    companion object {

        const val URL = "https://world.openfoodfacts.org/api/v2/product/{code}.json"
        /** Search-a-licious, the Open Food Facts search for words. */
        const val SEARCH_URL = "https://search.openfoodfacts.org/search"
        const val SEARCH_COUNTRY = "countries_tags:\"en:australia\""
        const val SEARCH_SIZE = 20
        const val USER_AGENT = "AAPS-SmartInsulin/1.0 (personal use)"
        const val MAX_SERVING_G = 2000.0
    }
}
