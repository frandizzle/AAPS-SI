package app.aaps.plugins.automation.elements

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import app.aaps.core.interfaces.resources.ResourceHelper

/**
 * Generic dropdown element for Automation action dialogs.
 * Element is an interface — implement directly, no superclass constructor.
 */
class InputDropdownMenu<T>(
    private val rh:          ResourceHelper,
    private val items:       List<T>,
    private val labelFn:     (T) -> String,
    initialValue:            T
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