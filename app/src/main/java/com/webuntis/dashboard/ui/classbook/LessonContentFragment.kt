package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.repeatOnLifecycle
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.databinding.FragmentLessonContentBinding
import com.webuntis.dashboard.databinding.ItemLessonContentEntryBinding
import com.webuntis.dashboard.databinding.ItemLessonContentHeaderBinding
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LessonContentFragment : Fragment() {

    private var _binding: FragmentLessonContentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LessonContentViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLessonContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = LessonContentAdapter()
        adapter.subjectStyle = viewModel.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.LESSON_CONTENT, com.webuntis.dashboard.model.NameType.SUBJECT)
        adapter.teacherStyle = viewModel.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.LESSON_CONTENT, com.webuntis.dashboard.model.NameType.TEACHER)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true, userInitiated = true) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.refreshing.collect { binding.swipeRefresh.isRefreshing = it }
            }
        }
        binding.btnLoadMore.setOnClickListener { viewModel.loadMoreDays() }
        binding.btnToggleGroupMode.setOnClickListener { viewModel.toggleGroupMode() }
        binding.btnFilterSubject.setOnClickListener { showSubjectFilterDialog() }
        binding.btnShare.setOnClickListener { shareCurrentList() }

        // Filter button label: "Alle Fächer" / the one chosen subject / "<n> Fächer".
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(viewModel.subjectFilter, viewModel.subjects) { f, s -> f to s }
                .collect { (filter, subjects) ->
                    binding.btnFilterSubject.text = when {
                        filter.isEmpty() -> getString(R.string.lesson_content_filter_all)
                        filter.size == 1 -> subjects.firstOrNull { it.key == filter.first() }?.label
                            ?: getString(R.string.lesson_content_filter_count, 1)
                        else -> getString(R.string.lesson_content_filter_count, filter.size)
                    }
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.swipeRefresh.isRefreshing = false
                when (state) {
                    is UiState.Loading -> {
                        binding.progressBar.isVisible = true
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = false
                        binding.btnLoadMore.isVisible = false
                    }
                    is UiState.Success -> {
                        binding.progressBar.isVisible = false
                        if (state.data.isEmpty()) {
                            binding.recyclerView.isVisible = false
                            binding.emptyView.isVisible = true
                            binding.emptyView.text = getString(
                                if (viewModel.subjectFilter.value.isNotEmpty()) R.string.lesson_content_filter_empty
                                else R.string.lesson_content_empty)
                            binding.btnLoadMore.isVisible = viewModel.canLoadMore.value
                        } else {
                            binding.recyclerView.isVisible = true
                            binding.emptyView.isVisible = false
                            adapter.submitGroups(state.data, viewModel.groupMode.value)
                            binding.btnLoadMore.isVisible = viewModel.canLoadMore.value
                        }
                    }
                    is UiState.Error -> {
                        binding.progressBar.isVisible = false
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = true
                        binding.emptyView.text = state.message
                        binding.btnLoadMore.isVisible = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.windowDays.collect { days ->
                binding.toolbar.subtitle = resources.getQuantityString(
                    R.plurals.lesson_content_subtitle_days, days, days
                )
                binding.btnLoadMore.isVisible = viewModel.canLoadMore.value &&
                    (viewModel.state.value is UiState.Success)
            }
        }

        // Button always shows the CURRENTLY active mode; tapping switches to the other one —
        // same convention as the Day/Week toggle on the timetable tab.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.groupMode.collect { mode ->
                binding.btnToggleGroupMode.text = getString(
                    if (mode == SessionManager.LessonContentGroupMode.BY_DAY)
                        R.string.lesson_content_group_by_day
                    else
                        R.string.lesson_content_group_by_subject
                )
                // Re-render immediately when the mode changes so the per-entry label
                // (date vs. subject) updates too, not just the section headers.
                (viewModel.state.value as? UiState.Success)?.let { adapter.submitGroups(it.data, mode) }
            }
        }
    }

    /** Multi-select of the subjects present in the loaded data; nothing selected = no filter. */
    private fun showSubjectFilterDialog() {
        val options = viewModel.subjects.value
        if (options.isEmpty()) return
        val selected = viewModel.subjectFilter.value
        val checked = BooleanArray(options.size) { options[it].key in selected }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lesson_content_filter_title)
            .setMultiChoiceItems(options.map { it.label }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setSubjectFilter(options.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet())
            }
            .setNeutralButton(R.string.lesson_content_filter_clear) { _, _ -> viewModel.setSubjectFilter(emptySet()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Senden an": exports exactly what is on screen (current filter and grouping) as plain text. */
    private fun shareCurrentList() {
        val groups = (viewModel.state.value as? UiState.Success)?.data?.takeIf { it.isNotEmpty() }
        if (groups == null) {
            android.widget.Toast.makeText(requireContext(), R.string.lesson_content_nothing_to_share, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mode = viewModel.groupMode.value
        val subjectStyle = viewModel.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.LESSON_CONTENT, com.webuntis.dashboard.model.NameType.SUBJECT)
        val teacherStyle = viewModel.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.LESSON_CONTENT, com.webuntis.dashboard.model.NameType.TEACHER)
        val days = viewModel.windowDays.value
        val title = resources.getQuantityString(R.plurals.lesson_content_export_title, days, days)
        val filter = viewModel.subjectFilter.value
        val filterLabels = viewModel.subjects.value.filter { it.key in filter }.joinToString(", ") { it.label }

        val text = buildString {
            appendLine(title)
            if (filter.isNotEmpty() && filterLabels.isNotBlank()) appendLine(getString(R.string.lesson_content_export_filter, filterLabels))
            groups.forEach { group ->
                appendLine()
                appendLine("${group.header} (${group.entries.size})")
                group.entries.forEach { e ->
                    // Same leading label as on screen: the one that is NOT already the group header.
                    val lead = if (mode == SessionManager.LessonContentGroupMode.BY_DAY)
                        e.displaySubject(subjectStyle.long, subjectStyle.shortInParens) else e.dateFormatted
                    val teacher = e.displayTeachers(teacherStyle.long, teacherStyle.shortInParens)
                    append("• ").append(lead).append(": ").append(e.teachingContent.orEmpty().trim())
                    if (teacher.isNotBlank()) append(" (").append(teacher).append(")")
                    appendLine()
                }
            }
        }.trimEnd()

        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(send, getString(R.string.lesson_content_share_chooser)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

sealed class ContentRow {
    data class Header(val label: String, val count: Int) : ContentRow()
    data class Entry(val entry: Lesson, val groupMode: SessionManager.LessonContentGroupMode) : ContentRow()
}

class LessonContentAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<ContentRow>()

    /** Per-screen name settings (Settings → Klar- und Kurznamen); read when the list is bound. */
    var subjectStyle: com.webuntis.dashboard.model.NameStyle = com.webuntis.dashboard.model.NameStyle(long = true)
    var teacherStyle: com.webuntis.dashboard.model.NameStyle = com.webuntis.dashboard.model.NameStyle()

    fun submitGroups(groups: List<ContentGroup>, groupMode: SessionManager.LessonContentGroupMode) {
        rows.clear()
        groups.forEach { group ->
            rows.add(ContentRow.Header(group.header, group.entries.size))
            group.entries.forEach { rows.add(ContentRow.Entry(it, groupMode)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is ContentRow.Header -> 0
        is ContentRow.Entry  -> 1
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(ItemLessonContentHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryVH(ItemLessonContentEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ContentRow.Header -> (holder as HeaderVH).bind(row)
            is ContentRow.Entry  -> (holder as EntryVH).bind(row.entry, row.groupMode, subjectStyle, teacherStyle)
        }
    }

    class HeaderVH(private val b: ItemLessonContentHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(header: ContentRow.Header) {
            b.textSubject.text = header.label
            b.textCount.text = b.root.resources.getQuantityString(
                R.plurals.lesson_content_entry_count, header.count, header.count
            )
        }
    }

    class EntryVH(private val b: ItemLessonContentEntryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(
            entry: Lesson,
            groupMode: SessionManager.LessonContentGroupMode,
            subjectStyle: com.webuntis.dashboard.model.NameStyle,
            teacherStyle: com.webuntis.dashboard.model.NameStyle
        ) {
            // Whichever of "date" / "subject" is ALREADY the section header is redundant here —
            // show the other one as the per-entry leading label instead.
            b.textLeadingLabel.text = if (groupMode == SessionManager.LessonContentGroupMode.BY_DAY) {
                entry.displaySubject(subjectStyle.long, subjectStyle.shortInParens)
            } else {
                entry.dateFormatted
            }
            b.textContent.text = entry.teachingContent ?: ""
            val teacher = entry.displayTeachers(teacherStyle.long, teacherStyle.shortInParens)
            b.textTeacher.text = teacher
            b.textTeacher.isVisible = teacher.isNotBlank()
            b.colorStripe.setBackgroundColor(
                entry.resolvedColor()
                    ?: com.webuntis.dashboard.ui.timetable.subjectColor(entry.subjectName, b.root.context)
            )
        }
    }
}
