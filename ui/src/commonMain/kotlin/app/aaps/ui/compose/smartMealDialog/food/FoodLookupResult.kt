package app.aaps.ui.compose.smartMealDialog.food

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

        /** The photo or text did not show food. */
        NOT_FOOD,

        /** The barcode is not in the food database, or it has no nutrition values. */
        NOT_FOUND,

        /** The answer could not be read. */
        BAD_RESPONSE,
        OTHER
    }
}
