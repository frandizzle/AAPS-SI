package app.aaps.plugins.automation.elements

import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import app.aaps.core.interfaces.resources.ResourceHelper

class InputDropdownMenu(private val rh: ResourceHelper) {

    private var itemList: ArrayList<CharSequence> = ArrayList()
    var value: String = ""

    constructor(rh: ResourceHelper, name: String) : this(rh) {
        value = name
    }

    @Suppress("unused")
    constructor(rh: ResourceHelper, another: InputDropdownMenu) : this(rh) {
        value = another.value
    }

    fun addToLayout(root: LinearLayout) {
        root.addView(
            Spinner(root.context).apply {
                adapter = ArrayAdapter(root.context, app.aaps.core.ui.R.layout.spinner_centered, itemList).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins(0, rh.dpToPx(4), 0, rh.dpToPx(4))
                }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        setValue(itemList[position].toString())
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                gravity = Gravity.CENTER_HORIZONTAL
                for (i in 0 until itemList.size) if (itemList[i] == value) setSelection(i)
            })
    }

    fun setValue(name: String): InputDropdownMenu {
        value = name
        return this
    }

    fun setList(values: ArrayList<CharSequence>) {
        itemList = ArrayList(values)
    }

    // For testing only
    fun add(item: String) {
        itemList.add(item)
    }
}

/**
 * Generic typed dropdown for ActionSmartMeal and future typed automation actions.
 */
class InputDropdownMenuTyped<T>(
    private val rh:      ResourceHelper,
    private val items:   List<T>,
    private val labelFn: (T) -> String,
    initialValue:        T
) {

    var value: T = initialValue

    fun addToLayout(root: LinearLayout) {
        val labels = items.map { labelFn(it) }
        root.addView(
            Spinner(root.context).apply {
                adapter = ArrayAdapter(root.context, app.aaps.core.ui.R.layout.spinner_centered, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins(0, rh.dpToPx(4), 0, rh.dpToPx(4))
                }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        value = items[position]
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                gravity = Gravity.CENTER_HORIZONTAL
                setSelection(items.indexOf(value).coerceAtLeast(0))
            })
    }
}
