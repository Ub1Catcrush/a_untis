package com.webuntis.dashboard.ui.changes

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.ChangeLogEntry
import com.webuntis.dashboard.api.ChangeSnapshot
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.databinding.DialogRecentChangesBinding
import com.webuntis.dashboard.databinding.ItemRecentChangeBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import javax.inject.Inject

/**
 * "Was ist neu" dialog opened from any of the change-check notifications (see
 * NotificationHelper / PlanChangeCheckWorker) — shows the actual list of recent changes
 * (schedule changes, new messages, homework, classbook entries) from the last 7 days, instead
 * of leaving the user with just a bare "45 Stundenplanänderungen" count.
 *
 * Purely a read-only view over [ChangeSnapshot.recentChanges]; it doesn't affect the
 * background worker's own 7-day dedup ledger. It only updates
 * [SessionManager.changesLastViewedAt], which controls the "NEU" dot shown per entry.
 */
@AndroidEntryPoint
class RecentChangesDialogFragment : DialogFragment() {

    @Inject lateinit var sessionManager: SessionManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogRecentChangesBinding.inflate(LayoutInflater.from(requireContext()))
        val gson = Gson()
        renderList(binding)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changes_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.changes_dialog_close, null)
            .setNegativeButton(R.string.changes_dialog_clear, null)
            .create()

        // Set the negative button's click listener AFTER show() so it doesn't auto-dismiss —
        // clearing the list should update the same dialog in place, not close it.
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // Keeps notifiedAt (the 7-day dedup ledger) untouched — this only clears what
                // the dialog itself shows, so already-notified changes don't get re-notified
                // just because their entry in this list was cleared.
                val snapshot = sessionManager.lastNotifiedSnapshot?.let { json -> ChangeSnapshot.parse(json, gson) }
                if (snapshot != null) {
                    sessionManager.lastNotifiedSnapshot = gson.toJson(snapshot.copy(recentChanges = emptyList()))
                }
                renderList(binding)
            }
        }
        return dialog
    }

    private fun renderList(binding: DialogRecentChangesBinding) {
        val gson = Gson()
        val snapshot = sessionManager.lastNotifiedSnapshot?.let { json -> ChangeSnapshot.parse(json, gson) }
        val cutoff = System.currentTimeMillis() - ChangeSnapshot.NOTIFIED_TTL_MS
        val entries = snapshot?.recentChanges
            ?.filter { it.timestampMs >= cutoff }
            ?.sortedByDescending { it.timestampMs }
            ?: emptyList()

        // Read BEFORE overwriting below, so entries seen for the first time in this dialog
        // still show their "NEU" dot while it's open.
        val previouslyViewedAt = sessionManager.changesLastViewedAt

        binding.textEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerChanges.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerChanges.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerChanges.adapter = ChangesAdapter(entries, previouslyViewedAt)

        sessionManager.changesLastViewedAt = System.currentTimeMillis()
    }

    private class ChangesAdapter(
        private val items: List<ChangeLogEntry>,
        private val previouslyViewedAt: Long
    ) : RecyclerView.Adapter<ChangesAdapter.ViewHolder>() {

        // New-since-last-view entries start open so the thing the user opened the dialog FOR
        // is immediately readable; everything already seen before starts collapsed to a single
        // line so a week of history doesn't turn this into a wall of text. Tapping any row
        // toggles it either way. Tracked by position rather than a stable entry id: this
        // adapter's input list is fixed for the dialog's lifetime (built once in
        // onCreateDialog, never resubmitted), so positions don't shift under it.
        private val expandedPositions = items.indices
            .filterTo(mutableSetOf()) { items[it].timestampMs > previouslyViewedAt }

        inner class ViewHolder(val binding: ItemRecentChangeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRecentChangeBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val binding = holder.binding
            val expanded = expandedPositions.contains(position)

            binding.textTitle.text = entry.title
            binding.textTitle.maxLines = if (expanded) 2 else 1

            // entry.text starts with the specific date/period ("Di, 30.09. · 3. Stunde
            // (10:40–11:25) · ...") — that's exactly the "which lesson, which day" detail that
            // must not depend on the user first discovering the row is tappable. So it stays
            // visible even collapsed (as a single truncated line); expanding only adds the
            // recorded-at timestamp, which is far less important than the lesson's own date.
            binding.textSubtitle.isVisible = true
            binding.textSubtitle.maxLines = if (expanded) 3 else 2
            binding.textSubtitle.text = if (expanded) {
                if (entry.text.isNotBlank()) "${entry.text}\n${formatTimestamp(entry.timestampMs)}"
                else formatTimestamp(entry.timestampMs)
            } else {
                entry.text.ifBlank { formatTimestamp(entry.timestampMs) }
            }
            binding.iconChevron.rotation = if (expanded) 180f else 0f

            binding.iconCategory.setImageResource(
                when (entry.category) {
                    "timetable" -> R.drawable.ic_calendar
                    "messages"  -> R.drawable.ic_message
                    "homework"  -> R.drawable.ic_homework
                    "classbook" -> R.drawable.ic_book
                    else        -> R.drawable.ic_calendar
                }
            )
            binding.dotUnread.visibility =
                if (entry.timestampMs > previouslyViewedAt) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                if (!expandedPositions.add(position)) expandedPositions.remove(position)
                notifyItemChanged(position)
            }
        }

        private fun formatTimestamp(ms: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(ms)
    }
}
