package app.aaps.ui.compose.smartMealDialog.food

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/** The answer to a meal estimate or a barcode lookup. */
sealed class FoodLookupResult {

    data class Success(val items: List<FoodItem>) : FoodLookupResult()

    /** [detail] is the service's own message, for the log and for "other" errors. May be empty. */
    data class Failure(val reason: Reason, val detail: String = "") : FoodLookupResult()

    enum class Reason {

        /** Meal estimates are off, or there is no API key. */
        NOT_SET_UP,
        OFFLINE,
        INVALID_KEY,

        /** Too many requests, or the free daily amount is used up. */
        LIMIT_REACHED,

        /** The service is too busy right now, also after trying again. */
        BUSY,

        /** No answer came in time. Not asked again: a second wait would be as long. */
        TIMEOUT,

        /** The photo or text did not show food. */
        NOT_FOOD,

        /** The barcode is not in the food database, or it has no nutrition values. */
        NOT_FOUND,

        /** The answer could not be read. */
        BAD_RESPONSE,
        OTHER
    }

    companion object {

        /** What a request that threw [e] failed with: a timeout gets its own reason, anything else shows its message. */
        fun failureOf(e: Exception): Failure = when (e) {
            is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> Failure(Reason.TIMEOUT, e.message.orEmpty())
            else                                                                                -> Failure(Reason.OTHER, e.message ?: e::class.simpleName.orEmpty())
        }
    }
}
