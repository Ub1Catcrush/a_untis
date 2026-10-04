package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.repeatOnLifecycle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.os.Bundle
import android.view.*
import com.webuntis.dashboard.R
import com.webuntis.dashboard.databinding.FragmentClassbookBinding
import com.webuntis.dashboard.ui.common.setupAccountSwitcher
import com.webuntis.dashboard.databinding.ItemClassbookBinding
import com.webuntis.dashboard.model.ClassbookEntry
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class ClassbookFragment : Fragment() {

    private var _binding: FragmentClassbookBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ClassbookViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClassbookBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = ClassbookAdapter()
        adapter.subjectStyle = viewModel.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.CLASSBOOK, com.webuntis.dashboard.model.NameType.SUBJECT)
        adapter.teacherStyle = viewModel.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.CLASSBOOK, com.webuntis.dashboard.model.NameType.TEACHER)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.nameCatalog.collect { adapter.nameCatalog = it }
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.load(forceRefresh = true, userInitiated = true) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.refreshing.collect { binding.swipeRefresh.isRefreshing = it }
            }
        }

        binding.toolbar.setupAccountSwitcher(viewModel.activeAccountManager)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.swipeRefresh.isRefreshing = false
                when (state) {
                    is UiState.Loading -> {
                        binding.progressBar.isVisible = true
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = false
                    }
                    is UiState.Success -> {
                        binding.progressBar.isVisible = false
                        updateSubtitle(state.data.size)
                        if (state.data.isEmpty()) {
                            binding.recyclerView.isVisible = false
                            binding.emptyView.isVisible = true
                            binding.emptyView.text = getString(R.string.label_no_classbook_entries)
                        } else {
                            binding.recyclerView.isVisible = true
                            binding.emptyView.isVisible = false
                            adapter.submitList(state.data)
                        }
                    }
                    is UiState.Error -> {
                        binding.progressBar.isVisible = false
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = true
                        binding.emptyView.text = state.message
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.schoolYearLabel.collect {
                val count = (viewModel.state.value as? UiState.Success)?.data?.size
                if (count != null) updateSubtitle(count)
            }
        }
    }

    private fun updateSubtitle(count: Int) {
        val yearLabel = viewModel.schoolYearLabel.value
        binding.toolbar.subtitle = if (yearLabel != null)
            "$yearLabel · ${getString(R.string.classbook_subtitle, count)}"
        else
            getString(R.string.classbook_subtitle, count)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

class ClassbookAdapter : ListAdapter<ClassbookEntry, ClassbookAdapter.VH>(Diff) {

    var subjectStyle: com.webuntis.dashboard.model.NameStyle = com.webuntis.dashboard.model.NameStyle()
    var teacherStyle: com.webuntis.dashboard.model.NameStyle = com.webuntis.dashboard.model.NameStyle()
    var nameCatalog: com.webuntis.dashboard.model.NameCatalog = com.webuntis.dashboard.model.NameCatalog()
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemClassbookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), subjectStyle, teacherStyle, nameCatalog)

    class VH(private val b: ItemClassbookBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(
            entry: ClassbookEntry,
            subjectStyle: com.webuntis.dashboard.model.NameStyle,
            teacherStyle: com.webuntis.dashboard.model.NameStyle,
            catalog: com.webuntis.dashboard.model.NameCatalog
        ) {
            // Short form keeps the old behaviour (elementName, then the short code); only an
            // explicit "long" setting swaps in the catalog's spelled-out name.
            b.textSubject.text = if (subjectStyle.long && !entry.subject.isNullOrBlank())
                catalog.subjectDisplay(entry.subject, subjectStyle)
            else entry.displaySubjectOrElement
            b.textContent.text = entry.displayText
            b.textDate.text = entry.dateFormatted ?: ""
            val teacherText = catalog.teacherDisplay(entry.teacher, teacherStyle)
            b.textTeacher.text = teacherText
            b.textTeacher.isVisible = teacherText.isNotEmpty()

            val cat = entry.displayCategory.lowercase()
            val (labelRes, bgRes, fgRes) = when {
                cat.contains("absen") || cat.contains("fehlen") ->
                    Triple("Abwesenheit", R.color.red_container, R.color.red)
                cat.contains("late") || cat.contains("spät") || cat.contains("verspät") ->
                    Triple("Verspätung", R.color.yellow_container, R.color.yellow)
                cat.contains("homework") || cat.contains("hausauf") ->
                    Triple("Hausaufgabe", R.color.blue_container, R.color.blue)
                cat.contains("note") || cat.contains("bemer") ->
                    Triple("Bemerkung", R.color.green_container, R.color.green)
                else -> Triple(entry.displayCategory, R.color.blue_container, R.color.blue)
            }
            b.categoryChip.text = labelRes
            b.categoryChip.setChipBackgroundColorResource(bgRes)
            b.categoryChip.setTextColor(ContextCompat.getColor(b.root.context, fgRes))
        }
    }

    object Diff : DiffUtil.ItemCallback<ClassbookEntry>() {
        override fun areItemsTheSame(a: ClassbookEntry, b: ClassbookEntry) = a.id == b.id
        override fun areContentsTheSame(a: ClassbookEntry, b: ClassbookEntry) = a == b
    }
}
