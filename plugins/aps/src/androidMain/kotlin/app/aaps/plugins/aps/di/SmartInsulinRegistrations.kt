package app.aaps.plugins.aps.di

import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.smartInsulin.MealOverrideManager
import app.aaps.core.interfaces.smartInsulin.SmartInsulinLearner
import app.aaps.core.interfaces.smartInsulin.SmartInsulinOverview
import app.aaps.plugins.aps.smartInsulin.MealOverrideManagerImpl
import app.aaps.plugins.aps.smartInsulin.ProfileLearner
import app.aaps.plugins.aps.smartInsulin.SmartInsulinPlugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides

/**
 * Registers SmartInsulin and the three interfaces it provides to the rest of the app.
 *
 * In 3.4 this was split between `ApsModule` (the overview binding) and `:app`'s `PluginsListModule`
 * (the plugin entry and the two manager bindings). 4.0 moved plugin registration out of `:app` and
 * into each plugin's own module, so all four live here.
 *
 * ## Plugin key 235
 *
 * SmartInsulin was key 230 in 3.4. In 4.0 that key belongs to OpenAPS AutoISF, and a duplicate key in an
 * `@IntoMap` silently replaces one entry with the other rather than failing — so reusing 230 would have
 * dropped one of the two plugins from the list without an error. 235 sits between AutoISF (230) and
 * Autotune (240), so SmartInsulin still lists next to the other APS plugins.
 *
 * Unqualified, for the reason spelled out on [ApsPluginRegistrations]: `:app` merges the unqualified
 * bucket unconditionally, which is what the old `@AllConfigs` meant.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object SmartInsulinRegistrations {

    @Provides
    @IntoMap
    @IntKey(235)
    fun smartInsulinEntry(plugin: SmartInsulinPlugin): PluginBase = plugin

    @Provides
    fun smartInsulinOverview(plugin: SmartInsulinPlugin): SmartInsulinOverview = plugin

    @Provides
    fun mealOverrideManager(impl: MealOverrideManagerImpl): MealOverrideManager = impl

    @Provides
    fun smartInsulinLearner(impl: ProfileLearner): SmartInsulinLearner = impl
}
