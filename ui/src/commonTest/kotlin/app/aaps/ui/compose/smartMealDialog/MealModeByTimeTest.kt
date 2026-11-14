package app.aaps.ui.compose.smartMealDialog

import app.aaps.core.interfaces.smartInsulin.MealMode
import app.aaps.ui.compose.smartMealDialog.SmartMealDialogViewModel.Companion.MealWindow
import app.aaps.ui.compose.smartMealDialog.SmartMealDialogViewModel.Companion.mealModeAt
import kotlin.test.Test
import kotlin.test.assertEquals

/** Smart Meal opens on the meal type of the clock time, from the UAM meal windows. */
class MealModeByTimeTest {

    // The user's times (14 Nov): breakfast 5 to 10, lunch 10 to 3 pm, afternoon 3 to 5 pm, dinner 5 to 9 pm, snack from 9 pm.
    private val windows = listOf(
        MealWindow(MealMode.BREAKFAST, 5 * 60, 10 * 60),
        MealWindow(MealMode.LUNCH, 10 * 60, 15 * 60),
        MealWindow(MealMode.DINNER, 17 * 60, 21 * 60),
        MealWindow(MealMode.EXTENDED, 21 * 60, 23 * 60),
        MealWindow(MealMode.AFTERNOON, 15 * 60, 17 * 60)
    )

    @Test
    fun eachTimeOfDayOpensItsOwnMeal() {
        assertEquals(MealMode.BREAKFAST, mealModeAt(7 * 60 + 30, windows))
        assertEquals(MealMode.LUNCH, mealModeAt(10 * 60, windows))           // the start belongs to the window
        assertEquals(MealMode.LUNCH, mealModeAt(14 * 60 + 59, windows))
        assertEquals(MealMode.AFTERNOON, mealModeAt(15 * 60, windows))       // the end does not
        assertEquals(MealMode.DINNER, mealModeAt(18 * 60 + 21, windows))
        assertEquals(MealMode.EXTENDED, mealModeAt(21 * 60 + 30, windows))
    }

    @Test
    fun outsideEveryWindowIsASnack() {
        assertEquals(MealMode.EXTENDED, mealModeAt(23 * 60 + 30, windows))
        assertEquals(MealMode.EXTENDED, mealModeAt(2 * 60, windows))
    }

    @Test
    fun aWindowCanRunPastMidnight() {
        val late = listOf(MealWindow(MealMode.EXTENDED, 21 * 60, 2 * 60))
        assertEquals(MealMode.EXTENDED, mealModeAt(1 * 60, late))
        assertEquals(MealMode.EXTENDED, mealModeAt(22 * 60, late))
    }
}
