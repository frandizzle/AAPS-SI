package app.aaps.plugins.automation.elements

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import app.aaps.core.interfaces.resources.ResourceHelper

/**
 * Original String-based dropdown — keeps full backward compatibility with
 * TriggerBTDevice, TriggerStepsCount, and any other existing triggers.
 */
class InputDropdownMenu(
    private val rh: ResourceHelper,
    private val label: String
) : Element {

    var value: String = ""
    private var list: List<String> = emptyList()
    private var spinner: Spinner? = null

    fun setList(newList: List<String>) {
        list = newList
        spinner?.let { updateAdapter(it) }
    }

    override fun addToLayout(root: LinearLayout) {
        val s = Spinner(root.context)
        spinner = s
        updateAdapter(s)
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                value = list.getOrElse(position) { "" }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(s)
    }

    private fun updateAdapter(s: Spinner) {
        val adapter = ArrayAdapter(s.context, android.R.layout.simple_spinner_item, list)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        s.adapter = adapter
        val idx = list.indexOf(value).coerceAtLeast(0)
        if (list.isNotEmpty()) s.setSelection(idx)
    }
}

/**
 * Generic typed dropdown for ActionSmartMeal and future typed actions.
 * Use this when items are an enum or typed list rather than raw strings.
 */
class InputDropdownMenuTyped<T>(
    private val rh:       ResourceHelper,
    private val items:    List<T>,
    private val labelFn:  (T) -> String,
    initialValue:         T
) : Element {

    var value: T = initialValue

    override fun addToLayout(root: LinearLayout) {
        val spinner = Spinner(root.context)
        val labels  = items.map { labelFn(it) }
        val adapter = ArrayAdapter(root.context, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(items.indexOf(value).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                value = items[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(spinner)
    }
}