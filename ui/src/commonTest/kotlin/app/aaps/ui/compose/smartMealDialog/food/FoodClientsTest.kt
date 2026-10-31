package app.aaps.ui.compose.smartMealDialog.food

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Gemini meal estimate and the Open Food Facts barcode lookup, against scripted answers. */
class FoodClientsTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var lastUrl = ""
    private var lastKey: String? = null

    private fun http(status: HttpStatusCode, body: String) = HttpClient(MockEngine { request ->
        lastUrl = request.url.toString()
        lastKey = request.headers["x-goog-api-key"]
        respond(ByteReadChannel(body), status, json)
    })

    /** A Gemini answer whose text part is [answer]. */
    private fun gemini(answer: String) =
        """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(answer)}}]},"finishReason":"STOP"}]}"""

    private val dinner = """{"items":[
        {"name":"White rice","portion":"1 cup cooked","portion_grams":158,"carbs_per_100g":28,"fibre_per_100g":0.4,"protein_per_100g":2.7,"fat_per_100g":0.3},
        {"name":"Chicken drumstick","portion":"2 drumsticks","portion_grams":"190","carbs_per_100g":0,"fibre_per_100g":0,"protein_per_100g":24,"fat_per_100g":9}
    ],"not_food":false}"""

    @Test
    fun `an estimate lists each food with its portion`() = runTest {
        val r = GeminiFoodClient(http(HttpStatusCode.OK, gemini(dinner))).estimate("key1", "gemini-2.5-flash", "rice and chicken", null, "AU")
        val items = assertIs<FoodLookupResult.Success>(r).items
        assertEquals(2, items.size)
        assertEquals("White rice", items[0].name)
        assertEquals(44.24, items[0].carbsG, 1e-9)
        assertEquals(190.0, items[1].baseGrams, 1e-9)   // a number sent as text is still read
        assertEquals(FoodSource.ESTIMATE, items[1].source)
        assertTrue(lastUrl.contains("/models/gemini-2.5-flash:generateContent"))
        assertEquals("key1", lastKey)
        assertTrue(!lastUrl.contains("key1"), "the key goes in a header, not in the address")
    }

    @Test
    fun `the request carries the description, the photo and the answer format`() {
        val client = GeminiFoodClient(HttpClient(MockEngine { respond("") }))
        val body = Json.parseToJsonElement(client.requestBody("1 cup rice", byteArrayOf(1, 2, 3), "AU")) as JsonObject
        val parts = ((body["contents"] as JsonArray)[0] as JsonObject)["parts"] as JsonArray
        val prompt = ((parts[0] as JsonObject)["text"] as JsonPrimitive).content
        assertTrue(prompt.contains("1 cup rice"))
        assertTrue(prompt.contains("AU"))
        assertTrue(prompt.contains("WITHOUT fibre"))
        val image = (parts[1] as JsonObject)["inline_data"] as JsonObject
        assertEquals("AQID", (image["data"] as JsonPrimitive).content)
        assertTrue((body["generationConfig"] as JsonObject).containsKey("responseSchema"))
    }

    @Test
    fun `no photo means no image part`() {
        val client = GeminiFoodClient(HttpClient(MockEngine { respond("") }))
        val body = Json.parseToJsonElement(client.requestBody("toast", null, "")) as JsonObject
        val parts = ((body["contents"] as JsonArray)[0] as JsonObject)["parts"] as JsonArray
        assertEquals(1, parts.size)
    }

    @Test
    fun `no food is said so`() {
        val client = GeminiFoodClient(HttpClient(MockEngine { respond("") }))
        val r = client.parseResponse(gemini("""{"items":[],"not_food":true}"""))
        assertEquals(FoodLookupResult.Reason.NOT_FOOD, assertIs<FoodLookupResult.Failure>(r).reason)
    }

    @Test
    fun `an answer in a code block is still read`() {
        val client = GeminiFoodClient(HttpClient(MockEngine { respond("") }))
        assertIs<FoodLookupResult.Success>(client.parseResponse(gemini("```json\n$dinner\n```")))
    }

    @Test
    fun `an item without a usable portion is left out`() {
        val client = GeminiFoodClient(HttpClient(MockEngine { respond("") }))
        val r = client.parseResponse(gemini("""{"items":[{"name":"Soup","portion":"","portion_grams":0,"carbs_per_100g":5,"fibre_per_100g":0,"protein_per_100g":1,"fat_per_100g":1}]}"""))
        assertIs<FoodLookupResult.Failure>(r)
    }

    @Test
    fun `a broken answer is not read as food`() {
        val client = GeminiFoodClient(HttpClient(MockEngine { respond("") }))
        assertEquals(FoodLookupResult.Reason.BAD_RESPONSE, assertIs<FoodLookupResult.Failure>(client.parseResponse("<html>")).reason)
        assertEquals(FoodLookupResult.Reason.BAD_RESPONSE, assertIs<FoodLookupResult.Failure>(client.parseResponse(gemini("not json"))).reason)
    }

    @Test
    fun `a refused key and a used-up limit are told apart`() = runTest {
        val bad = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        val r1 = GeminiFoodClient(http(HttpStatusCode.BadRequest, bad)).estimate("k", "m", "x", null, "")
        assertEquals(FoodLookupResult.Reason.INVALID_KEY, assertIs<FoodLookupResult.Failure>(r1).reason)
        val r2 = GeminiFoodClient(http(HttpStatusCode.TooManyRequests, """{"error":{"message":"Quota exceeded"}}""")).estimate("k", "m", "x", null, "")
        assertEquals(FoodLookupResult.Reason.LIMIT_REACHED, assertIs<FoodLookupResult.Failure>(r2).reason)
    }

    @Test
    fun `a busy service is asked again, and said so when it stays busy`() = runTest {
        var calls = 0
        val busyOnce = HttpClient(MockEngine {
            calls++
            if (calls == 1) respond(ByteReadChannel("""{"error":{"message":"high demand"}}"""), HttpStatusCode.ServiceUnavailable, json)
            else respond(ByteReadChannel(gemini(dinner)), HttpStatusCode.OK, json)
        })
        assertIs<FoodLookupResult.Success>(GeminiFoodClient(busyOnce).estimate("k", "m", "x", null, ""))
        assertEquals(2, calls)

        calls = 0
        val alwaysBusy = HttpClient(MockEngine { calls++; respond(ByteReadChannel("{}"), HttpStatusCode.ServiceUnavailable, json) })
        val r = GeminiFoodClient(alwaysBusy).estimate("k", "m", "x", null, "")
        assertEquals(FoodLookupResult.Reason.BUSY, assertIs<FoodLookupResult.Failure>(r).reason)
        assertEquals(1 + GeminiFoodClient.RETRY_DELAYS_MS.size, calls)
    }

    @Test
    fun `a timeout says so, and is not asked again`() = runTest {
        var calls = 0
        val slow = HttpClient(MockEngine { calls++; throw SocketTimeoutException("Socket timeout has expired") })
        val r = GeminiFoodClient(slow).estimate("k", "m", "x", null, "")
        assertEquals(FoodLookupResult.Reason.TIMEOUT, assertIs<FoodLookupResult.Failure>(r).reason)
        assertEquals(1, calls)
        val other = HttpClient(MockEngine { throw IllegalStateException("broken") })
        val r2 = assertIs<FoodLookupResult.Failure>(GeminiFoodClient(other).estimate("k", "m", "x", null, ""))
        assertEquals(FoodLookupResult.Reason.OTHER, r2.reason)
        assertEquals("broken", r2.detail)
    }

    // --- Open Food Facts ---

    private val yoghurt = """{"status":1,"product":{"product_name":"Greek yoghurt","brands":"Chobani, Mondelez",
        "serving_size":"170 g","serving_quantity":170,"countries_tags":["en:australia"],
        "nutriments":{"carbohydrates_100g":4.2,"fiber_100g":0,"proteins_100g":9.5,"fat_100g":"3.1"}}}"""

    @Test
    fun `a scanned product is one serving, and its amount can be doubled`() = runTest {
        val r = OpenFoodFactsClient(http(HttpStatusCode.OK, yoghurt)).lookup("9310036000000")
        val item = assertIs<FoodLookupResult.Success>(r).items.single()
        assertEquals("Chobani Greek yoghurt", item.name)
        assertEquals(170.0, item.baseGrams, 1e-9)
        assertEquals("170 g", item.portion)
        assertEquals(FoodSource.BARCODE, item.source)
        assertEquals(7.14, item.carbsG, 1e-9)
        assertEquals(14.28, item.copy(quantity = 2.0).carbsG, 1e-9)
        assertEquals(5.27, item.fatG, 1e-9)
        assertTrue(lastUrl.contains("/product/9310036000000.json"))
    }

    @Test
    fun `a product without a serving size counts 100 g`() {
        val r = OpenFoodFactsClient(HttpClient(MockEngine { respond("") }))
            .parse("""{"status":1,"product":{"product_name":"Oats","nutriments":{"carbohydrates_100g":60}}}""", "12345678")
        val item = assertIs<FoodLookupResult.Success>(r).items.single()
        assertEquals(100.0, item.baseGrams, 1e-9)
        assertEquals("", item.portion)
    }

    @Test
    fun `a US only label has its fibre taken off the carbs`() {
        val r = OpenFoodFactsClient(HttpClient(MockEngine { respond("") })).parse(
            """{"status":1,"product":{"product_name":"Bran","countries_tags":["en:united-states"],"nutriments":{"carbohydrates_100g":70,"fiber_100g":30}}}""",
            "12345678"
        )
        assertEquals(40.0, assertIs<FoodLookupResult.Success>(r).items.single().carbsPer100, 1e-9)
    }

    @Test
    fun `an unknown product or one without carbs is not found`() = runTest {
        val notThere = OpenFoodFactsClient(http(HttpStatusCode.OK, """{"status":0,"status_verbose":"product not found"}""")).lookup("12345678")
        assertEquals(FoodLookupResult.Reason.NOT_FOUND, assertIs<FoodLookupResult.Failure>(notThere).reason)
        val noCarbs = OpenFoodFactsClient(HttpClient(MockEngine { respond("") }))
            .parse("""{"status":1,"product":{"product_name":"Water","nutriments":{}}}""", "12345678")
        assertEquals(FoodLookupResult.Reason.NOT_FOUND, assertIs<FoodLookupResult.Failure>(noCarbs).reason)
    }

    @Test
    fun `a code that is not a food barcode is not sent`() = runTest {
        lastUrl = ""
        val r = OpenFoodFactsClient(http(HttpStatusCode.OK, yoghurt)).lookup("12ab")
        assertEquals(FoodLookupResult.Reason.NOT_FOUND, assertIs<FoodLookupResult.Failure>(r).reason)
        assertEquals("", lastUrl)
        assertNull(lastKey)
    }
}
