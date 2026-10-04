package com.webuntis.dashboard.ui.login

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import androidx.core.view.isVisible
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.School

/**
 * Dropdown adapter for the school search. The list comes from the server (already filtered),
 * so the built-in prefix filtering of [ArrayAdapter] is replaced by a pass-through filter —
 * otherwise results like "Gymnasium X" would vanish when the user typed the town name.
 */
class SchoolSuggestionAdapter(context: Context) :
    ArrayAdapter<School>(context, R.layout.item_school_suggestion, mutableListOf()) {

    fun submit(schools: List<School>) {
        clear()
        addAll(schools)
    }

    /** True if [text] is exactly the label of a currently offered school (= just selected). */
    fun hasLabel(text: String): Boolean = (0 until count).any { getItem(it)?.displayName == text }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_school_suggestion, parent, false)
        val school = getItem(position)
        view.findViewById<TextView>(R.id.school_name).text = school?.displayName ?: school?.loginName
        view.findViewById<TextView>(R.id.school_address).apply {
            text = school?.address
            isVisible = !school?.address.isNullOrBlank()
        }
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().apply { count = this@SchoolSuggestionAdapter.count }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            if (count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? School)?.displayName ?: ""
    }
}
