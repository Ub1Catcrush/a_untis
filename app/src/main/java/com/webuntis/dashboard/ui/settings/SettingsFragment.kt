package com.webuntis.dashboard.ui.settings

import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import android.view.View
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.webuntis.dashboard.BuildConfig
import com.webuntis.dashboard.R
import com.webuntis.dashboard.api.NotificationCategory
import com.webuntis.dashboard.model.NameType
import com.webuntis.dashboard.model.NameStyle
import com.webuntis.dashboard.model.NameScreen
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.api.UpdateManager
import com.webuntis.dashboard.databinding.FragmentSettingsBinding
import com.webuntis.dashboard.ui.login.LoginState
import com.webuntis.dashboard.ui.login.LoginViewModel
import com.webuntis.dashboard.ui.login.SecondAccountState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val loginViewModel: LoginViewModel by viewModels(
        ownerProducer = { requireActivity() }
    )

    @Inject
    lateinit var updateManager: UpdateManager

    // ── Export / Import launchers ─────────────────────────────────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loginViewModel.sessionManager.notificationsEnabled = true
            com.webuntis.dashboard.api.NotificationScheduler.start(requireContext())
            updateBatteryOptimizationUi()
            updateNotificationCategoriesEnabled()
        } else {
            // Permission denied — leave the feature off and reflect that in the switch so
            // it doesn't silently claim to be enabled while no notification can ever show.
            binding.switchNotificationsEnabled.isChecked = false
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.settings_notifications_permission_denied), android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = loginViewModel.sessionManager.exportSettings()
            requireContext().contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_export_ok), android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@registerForActivityResult
            when (val result = loginViewModel.sessionManager.importSettings(json)) {
                is com.webuntis.dashboard.api.SessionManager.ImportResult.Success -> {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.settings_import_ok), android.widget.Toast.LENGTH_LONG).show()
                    val session = loginViewModel.sessionManager.session
                    val creds   = loginViewModel.sessionManager.storedCredentials
                    if (session != null && creds != null) {
                        loginViewModel.login(session.server, session.schoolname, creds.first, creds.second)
                    }
                    bindCurrentValues()
                    bindNotificationCategories()
                    buildNameStyleSettings()
                    if (result.secondUpdated) renderAdditionalAccountsList()
                }
                is com.webuntis.dashboard.api.SessionManager.ImportResult.Error ->
                    android.widget.Toast.makeText(requireContext(), result.message, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_import_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSave.setOnClickListener { saveAndReLogin() }
        binding.btnLogout.setOnClickListener { loginViewModel.logout() }
        binding.btnExportSettings.setOnClickListener { exportLauncher.launch("webuntis_settings.json") }
        binding.btnImportSettings.setOnClickListener { importLauncher.launch("application/json") }

        // ── Update Section ────────────────────────────────────────────────────
        binding.textVersionInfo.text = getString(R.string.settings_update_version_info, BuildConfig.VERSION_NAME)
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }

        // ── Timetable days slider ─────────────────────────────────────────────
        val current = loginViewModel.sessionManager.timetableDays
        binding.seekerTimetableDays.max = SessionManager.MAX_TIMETABLE_DAYS - SessionManager.MIN_TIMETABLE_DAYS
        binding.seekerTimetableDays.progress = current - SessionManager.MIN_TIMETABLE_DAYS
        binding.textTimetableDaysValue.text = current.toString()

        binding.seekerTimetableDays.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val days = progress + SessionManager.MIN_TIMETABLE_DAYS
                    binding.textTimetableDaysValue.text = days.toString()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val days = (sb?.progress ?: 0) + SessionManager.MIN_TIMETABLE_DAYS
                    loginViewModel.sessionManager.timetableDays = days
                    loginViewModel.refreshTimetable()
                }
            }
        )

        binding.btnCheckNow.setOnClickListener { runCheckNow() }
        binding.btnShowChanges.setOnClickListener { findNavController().navigate(R.id.recentChangesDialogFragment) }

        // ── Unterrichtsinhalte: default day-window ──────────────────────────────
        when (loginViewModel.sessionManager.lessonContentDefaultDays) {
            7  -> binding.radioLessonContentDays7.isChecked = true
            30 -> binding.radioLessonContentDays30.isChecked = true
            60 -> binding.radioLessonContentDays60.isChecked = true
            else -> binding.radioLessonContentDays14.isChecked = true
        }
        binding.radioGroupLessonContentDays.setOnCheckedChangeListener { _, checkedId ->
            loginViewModel.sessionManager.lessonContentDefaultDays = when (checkedId) {
                R.id.radio_lesson_content_days_7  -> 7
                R.id.radio_lesson_content_days_30 -> 30
                R.id.radio_lesson_content_days_60 -> 60
                else                               -> 14
            }
        }

        // ── Klar-/Kurznamen, separately per screen ────────────────────────────
        buildNameStyleSettings()

        // ── Cache TTL slider ──────────────────────────────────────────────────
        fun cacheTtlLabel(min: Int) = if (min == 0)
            getString(R.string.settings_cache_off)
        else
            getString(R.string.settings_cache_minutes, min)

        val currentTtl = loginViewModel.sessionManager.cacheTtlMinutes
        binding.seekerCacheTtl.max = SessionManager.MAX_CACHE_TTL
        binding.seekerCacheTtl.progress = currentTtl
        binding.textCacheTtlValue.text = cacheTtlLabel(currentTtl)

        binding.seekerCacheTtl.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.textCacheTtlValue.text = cacheTtlLabel(progress)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val ttl = sb?.progress ?: SessionManager.DEFAULT_CACHE_TTL
                    loginViewModel.sessionManager.cacheTtlMinutes = ttl
                    loginViewModel.clearDataCaches()
                }
            }
        )

        bindCurrentValues()

        binding.btnSaveSecond.setOnClickListener {
            val label    = binding.inputSecondLabel.text.toString().trim()
            val username = binding.inputSecondUsername.text.toString().trim()
            val password = binding.inputSecondPassword.text.toString()
            if (username.isBlank() || password.isBlank()) {
                binding.statusSecond.text = getString(R.string.settings_second_error_credentials)
                binding.statusSecond.isVisible = true
                return@setOnClickListener
            }
            loginViewModel.saveSecondAccount(username, password, label)
        }

        renderAdditionalAccountsList()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.secondAccountState.collect { state ->
                    when (state) {
                        is SecondAccountState.Loading -> {
                            binding.btnSaveSecond.isEnabled = false
                            binding.statusSecond.text = getString(R.string.settings_second_connecting)
                            binding.statusSecond.isVisible = true
                        }
                        is SecondAccountState.Saved -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.statusSecond.text = getString(R.string.settings_second_saved_prefix, state.info)
                            binding.statusSecond.isVisible = true
                            // Clear the form so it's ready for the next child, rather than
                            // looking like it's still showing/editing the one just added.
                            binding.inputSecondLabel.text?.clear()
                            binding.inputSecondUsername.text?.clear()
                            binding.inputSecondPassword.text?.clear()
                            renderAdditionalAccountsList()
                        }
                        is SecondAccountState.Removed -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.statusSecond.text = getString(R.string.settings_second_removed)
                            binding.statusSecond.isVisible = true
                            renderAdditionalAccountsList()
                        }
                        is SecondAccountState.Error -> {
                            binding.btnSaveSecond.isEnabled = true
                            binding.statusSecond.text = getString(R.string.settings_second_error_prefix, state.message)
                            binding.statusSecond.isVisible = true
                        }
                        else -> {}
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.loginState.collect { state ->
                    binding.progressBar.isVisible = state is LoginState.Loading
                    binding.btnSave.isEnabled = state !is LoginState.Loading
                    when (state) {
                        is LoginState.Success -> {
                            binding.statusText.text = getString(R.string.settings_saved_ok)
                            binding.statusText.isVisible = true
                        }
                        is LoginState.Error -> {
                            binding.statusText.text = state.message
                            binding.statusText.isVisible = true
                        }
                        else -> binding.statusText.isVisible = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.isLoggedIn.collect { loggedIn ->
                    if (!loggedIn) {
                        findNavController().navigate(R.id.loginFragment)
                    }
                }
            }
        }
    }

    /** One switch per [NotificationCategory]; each mirrors an Android notification channel. */
    private fun notificationCategorySwitches() = listOf(
        NotificationCategory.CANCELLATIONS to binding.switchNotifCancellations,
        NotificationCategory.SUBSTITUTIONS to binding.switchNotifSubstitutions,
        NotificationCategory.ROOM_CHANGES  to binding.switchNotifRoomChanges,
        NotificationCategory.MESSAGES      to binding.switchNotifMessages,
        NotificationCategory.HOMEWORK      to binding.switchNotifHomework,
        NotificationCategory.CLASSBOOK     to binding.switchNotifClassbook
    )

    private fun bindNotificationCategories() {
        notificationCategorySwitches().forEach { (category, toggle) ->
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = loginViewModel.sessionManager.isNotificationCategoryEnabled(category)
            toggle.setOnCheckedChangeListener { _, checked ->
                loginViewModel.sessionManager.setNotificationCategoryEnabled(category, checked)
            }
        }
        updateNotificationCategoriesEnabled()
    }

    /** The per-category switches only make sense while the master switch is on. */
    private fun updateNotificationCategoriesEnabled() {
        val enabled = loginViewModel.sessionManager.notificationsEnabled
        notificationCategorySwitches().forEach { (_, toggle) -> toggle.isEnabled = enabled }
    }

    /** Opens Android's own per-channel notification settings for this app. */
    private fun openSystemNotificationSettings() {
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            })
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_notifications_system_failed), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Builds one collapsible block per screen with, per name type, a "spell out" switch and
     *  (while that is on) a "show abbreviation in parentheses" switch below it. The week view
     *  block additionally lets you pick what the second tile line shows and styles that line. */
    private fun buildNameStyleSettings() {
        val container = binding.namesContainer
        container.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val mutedColor = com.google.android.material.color.MaterialColors.getColor(
            container, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val sm = loginViewModel.sessionManager

        fun screenTitle(screen: NameScreen) = getString(when (screen) {
            NameScreen.DAY_VIEW        -> R.string.settings_names_screen_day
            NameScreen.WEEK_VIEW,
            NameScreen.WEEK_VIEW_LINE2 -> R.string.settings_names_screen_week
            NameScreen.LESSON_CONTENT  -> R.string.settings_names_screen_lesson_content
            NameScreen.CLASSBOOK       -> R.string.settings_names_screen_classbook
            NameScreen.HOMEWORK        -> R.string.settings_names_screen_homework
            NameScreen.MESSAGES        -> R.string.settings_names_screen_messages
            NameScreen.EVENTS          -> R.string.settings_names_screen_events
        })

        fun longLabel(screen: NameScreen, type: NameType) = getString(when {
            screen == NameScreen.MESSAGES -> R.string.settings_names_teacher_messages
            type == NameType.SUBJECT      -> R.string.settings_show_long_subjects
            type == NameType.TEACHER      -> R.string.settings_show_long_teachers
            else                          -> R.string.settings_show_long_rooms
        })

        /** The abbreviation example has to match what is being named (subject vs. teacher vs. room). */
        fun parensLabel(screen: NameScreen, type: NameType) = getString(when {
            screen == NameScreen.MESSAGES -> R.string.settings_show_short_sender_in_parens
            type == NameType.SUBJECT      -> R.string.settings_show_short_in_parens
            type == NameType.TEACHER      -> R.string.settings_show_short_teacher_in_parens
            else                          -> R.string.settings_show_short_room_in_parens
        })

        /** Adds the "spell out" + "abbreviation in parentheses" switch pair for one name type. */
        fun addStyleSwitches(parent: LinearLayout, screen: NameScreen, type: NameType) {
            val style = sm.nameStyle(screen, type)
            val parens = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
                text = parensLabel(screen, type)
                textSize = 13f
                setTextColor(mutedColor)
                minHeight = dp(40)
                isChecked = style.shortInParens
                isEnabled = style.long
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(16) }
            }
            val long = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
                text = longLabel(screen, type)
                textSize = 14f
                minHeight = dp(48)
                isChecked = style.long
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            fun save() {
                sm.setNameStyle(screen, type, NameStyle(long.isChecked, parens.isChecked))
                loginViewModel.clearDataCaches(); loginViewModel.refreshTimetable()
            }
            long.setOnCheckedChangeListener { _, checked -> parens.isEnabled = checked; save() }
            parens.setOnCheckedChangeListener { _, _ -> save() }
            parent.addView(long)
            parent.addView(parens)
        }

        fun sectionLabel(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(8), 0, dp(2))
        }

        /** "What does this tile line show" radio group + the style switches of the chosen content. */
        fun addLineControls(
            body: LinearLayout,
            label: Int,
            styleScreen: NameScreen,
            allowNone: Boolean,
            get: () -> SessionManager.WeekViewLine,
            set: (SessionManager.WeekViewLine) -> Unit
        ) {
            body.addView(sectionLabel(getString(label)))
            val lineStyles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            fun rebuildLineStyles(mode: SessionManager.WeekViewLine) {
                lineStyles.removeAllViews()
                val type = when (mode) {
                    SessionManager.WeekViewLine.SUBJECT -> NameType.SUBJECT
                    SessionManager.WeekViewLine.TEACHER -> NameType.TEACHER
                    SessionManager.WeekViewLine.ROOM    -> NameType.ROOM
                    SessionManager.WeekViewLine.NONE    -> return
                }
                addStyleSwitches(lineStyles, styleScreen, type)
            }

            val options = listOfNotNull(
                SessionManager.WeekViewLine.SUBJECT to R.string.settings_week_view_second_line_subject,
                SessionManager.WeekViewLine.TEACHER to R.string.settings_week_view_second_line_teacher,
                SessionManager.WeekViewLine.ROOM    to R.string.settings_week_view_second_line_room,
                if (allowNone) SessionManager.WeekViewLine.NONE to R.string.settings_week_view_second_line_none else null
            )
            val group = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.VERTICAL }
            val ids = options.associate { (mode, text) ->
                val rb = android.widget.RadioButton(ctx).apply {
                    id = View.generateViewId()
                    this.text = getString(text)
                    minHeight = dp(40)
                }
                group.addView(rb)
                rb.id to mode
            }
            val current = get()
            ids.entries.first { it.value == current }.key.let { group.check(it) }
            group.setOnCheckedChangeListener { _, checkedId ->
                val mode = ids[checkedId] ?: return@setOnCheckedChangeListener
                set(mode)
                rebuildLineStyles(mode)
                loginViewModel.refreshTimetable()
            }
            body.addView(group)
            rebuildLineStyles(current)
            body.addView(lineStyles)
        }

        fun addWeekViewControls(body: LinearLayout) {
            addLineControls(body, R.string.settings_week_first_line, NameScreen.WEEK_VIEW, allowNone = false,
                get = { sm.weekViewFirstLine }, set = { sm.weekViewFirstLine = it })
            addLineControls(body, R.string.settings_week_second_line_label, NameScreen.WEEK_VIEW_LINE2, allowNone = true,
                get = { sm.weekViewSecondLine }, set = { sm.weekViewSecondLine = it })
        }

        NameScreen.values().forEach { screen ->
            if (screen == NameScreen.WEEK_VIEW_LINE2) return@forEach   // part of the week-view block
            val title = screenTitle(screen)
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(8), 0, 0, dp(4))
            }
            val header = TextView(ctx).apply {
                text = "▸  $title"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                minHeight = dp(44)
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    text = (if (open) "▾  " else "▸  ") + title
                }
            }
            if (screen == NameScreen.WEEK_VIEW) addWeekViewControls(body)
            else screen.types.forEach { type -> addStyleSwitches(body, screen, type) }
            container.addView(header)
            container.addView(body)
        }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.settings_update_checking)

        viewLifecycleOwner.lifecycleScope.launch {
            updateManager.checkForUpdates().onSuccess { info ->
                if (info.hasUpdate && info.downloadUrl != null) {
                    binding.btnCheckUpdate.text = getString(R.string.settings_update_download_install)
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.setOnClickListener {
                        updateManager.downloadAndInstall(info.downloadUrl, "webuntis-dashboard-${info.latestVersion}.apk")
                        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_update_download_started), android.widget.Toast.LENGTH_SHORT).show()
                    }

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.settings_update_dialog_title))
                        .setMessage(getString(R.string.settings_update_dialog_message, info.latestVersion, info.releaseNotes ?: ""))
                        .setPositiveButton(getString(R.string.settings_update_dialog_install)) { _, _ ->
                             updateManager.downloadAndInstall(info.downloadUrl, "webuntis-dashboard-${info.latestVersion}.apk")
                        }
                        .setNegativeButton(getString(R.string.settings_update_dialog_later), null)
                        .show()
                } else {
                    binding.btnCheckUpdate.text = getString(R.string.settings_update_no_update)
                    binding.btnCheckUpdate.isEnabled = false
                }
            }.onFailure {
                binding.btnCheckUpdate.text = getString(R.string.settings_update_error)
                binding.btnCheckUpdate.isEnabled = true
                android.widget.Toast.makeText(requireContext(), getString(R.string.settings_update_check_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindCurrentValues() {
        val session = loginViewModel.sessionManager.session
        if (session != null) {
            binding.inputServer.setText(session.server)
            binding.inputSchoolname.setText(session.schoolname)
            binding.inputUsername.setText(
                loginViewModel.sessionManager.storedCredentials?.first ?: session.username)
            binding.inputMainAlias.setText(loginViewModel.sessionManager.mainAccountAlias ?: "")
            // Shows what will be used if the field is left empty, without duplicating the
            // actual default logic (SessionManager.mainAccountLabel is the single source of
            // truth for it — this just previews it for the currently signed-in role).
            val defaultAlias = when {
                session.isParent  -> "Eltern"
                session.isStudent -> "Kind"
                else              -> session.accountTypeLabel
            }
            binding.inputMainAlias.hint = getString(R.string.settings_main_alias_hint_default, defaultAlias)
        }
        binding.inputPassword.hint = if (loginViewModel.sessionManager.storedCredentials != null)
            getString(R.string.login_password_saved_hint)
        else getString(R.string.login_password_hint)
        renderAdditionalAccountsList()
    }

    /**
     * Renders one row per configured additional (child) account into layout_additional_accounts,
     * each with its own "Entfernen" button — replaces the single-account show/hide-remove-button
     * approach now that there can be several. Built programmatically rather than via a
     * RecyclerView + adapter: this list is short (a handful of children at most) and lives on an
     * already-scrolling settings screen, so the extra machinery isn't worth it here.
     */
    private fun renderAdditionalAccountsList() {
        val container = binding.layoutAdditionalAccounts
        container.removeAllViews()
        val accounts = loginViewModel.additionalAccounts
        if (accounts.isEmpty()) {
            val empty = android.widget.TextView(requireContext()).apply {
                text = getString(R.string.settings_second_no_accounts)
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                textSize = 13f
                setPadding(0, 0, 0, 12)
            }
            container.addView(empty)
            return
        }
        accounts.forEach { account ->
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 12)
            }
            val info = buildString {
                if (account.personName.isNotBlank()) append(account.personName) else append(account.username)
                if (account.label.isNotBlank() && account.label != account.personName) {
                    append(" (").append(account.label).append(")")
                }
            }
            val label = android.widget.TextView(requireContext()).apply {
                text = info
                textSize = 14f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val removeBtn = com.google.android.material.button.MaterialButton(
                requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = getString(R.string.settings_second_remove_button)
                setOnClickListener { loginViewModel.removeAdditionalAccount(account.key) }
            }
            row.addView(label)
            row.addView(removeBtn)
            container.addView(row)
        }
    }

    private fun saveAndReLogin() {
        val server     = binding.inputServer.text.toString().trim()
        val schoolname = binding.inputSchoolname.text.toString().trim()
        val username   = binding.inputUsername.text.toString().trim()
        val typed      = binding.inputPassword.text.toString()
        // Use stored password when field is left blank (common case when only changing other fields)
        val password   = typed.ifBlank {
            loginViewModel.sessionManager.storedCredentials?.second ?: ""
        }

        if (server.isBlank() || schoolname.isBlank() || username.isBlank() || password.isBlank()) {
            binding.statusText.text = getString(R.string.error_fill_all_fields)
            binding.statusText.isVisible = true
            return
        }
        // Persisted independently of the login call itself — doesn't need a re-login to apply.
        loginViewModel.sessionManager.mainAccountAlias = binding.inputMainAlias.text?.toString()
        // Do NOT clearSession() here — it would wipe storedCredentials before login() saves them
        loginViewModel.login(server, schoolname, username, password)
    }

    /**
     * Shows/hides the "disable battery optimization" hint depending on both whether the
     * feature is even on and whether the OS already exempts the app. Without this exemption,
     * OEM battery managers (Samsung, Xiaomi, etc. — especially on Android 14/15, which
     * tightened background execution further) routinely kill the periodic WorkManager job
     * before it ever runs, so change-check notifications can silently never appear even
     * though the feature is correctly enabled and everything else about it works.
     */
    /**
     * Runs PlanChangeCheckWorker immediately via NotificationScheduler.checkNow() and reports
     * back once it finishes, instead of the user having to wait up to 15 minutes for the next
     * periodic slot and then having no way to tell "nothing changed" apart from "the pipeline
     * is broken" if no notification shows up.
     */
    private fun runCheckNow() {
        if (!loginViewModel.sessionManager.notificationsEnabled) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_disabled), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val requestId = com.webuntis.dashboard.api.NotificationScheduler.checkNow(requireContext())
        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_running), android.widget.Toast.LENGTH_SHORT).show()
        androidx.work.WorkManager.getInstance(requireContext())
            .getWorkInfoByIdLiveData(requestId)
            .observe(viewLifecycleOwner) { info ->
                if (info == null) return@observe
                when (info.state) {
                    androidx.work.WorkInfo.State.SUCCEEDED ->
                        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_done), android.widget.Toast.LENGTH_LONG).show()
                    androidx.work.WorkInfo.State.FAILED ->
                        android.widget.Toast.makeText(requireContext(), getString(R.string.settings_check_now_failed), android.widget.Toast.LENGTH_LONG).show()
                    else -> {}
                }
            }
    }

    private fun updateBatteryOptimizationUi() {
        val enabled = loginViewModel.sessionManager.notificationsEnabled
        val exempted = isIgnoringBatteryOptimizations()
        val showHint = enabled && !exempted
        binding.btnBatteryOptimization.isVisible = showHint
        binding.textBatteryOptimizationHint.isVisible = showHint

        // Battery-optimization exemption alone isn't enough on several aggressive OEM ROMs
        // (see requestAutostartPermission) — surface that as a separate action so it isn't
        // missed. Shown alongside the battery hint above (not only once that one's dealt with):
        // on these ROMs both are typically needed, and there's no reliable way to detect
        // whether autostart is already granted the way isIgnoringBatteryOptimizations() can for
        // battery, so this stays visible whenever notifications are on and the device is a
        // known-affected manufacturer, letting the user re-check it any time.
        val showAutostart = enabled && isKnownAggressiveManufacturer()
        binding.btnAutostart.isVisible = showAutostart
        binding.textAutostartHint.isVisible = showAutostart
    }

    private fun isKnownAggressiveManufacturer(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return manufacturer in setOf("xiaomi", "poco", "redmi", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "meizu", "asus", "letv", "zte", "lenovo", "nokia")
    }

    /**
     * Best-effort deep link into the manufacturer's own "autostart" / "manage app launch"
     * screen. On MIUI (Xiaomi/Redmi/POCO) and several other ROMs, a background service can be
     * fully exempted from battery optimization and STILL never get to run in the background —
     * there's a second, ROM-specific permission gate on top that Android's standard APIs don't
     * cover at all, which is why isIgnoringBatteryOptimizations() can't detect it and this
     * button has to stay visible unconditionally on these devices (see updateBatteryOptimizationUi).
     * These component names are undocumented/unofficial and can change between ROM versions, so
     * several are tried in order before giving up and pointing the user at the app's own
     * settings page as a fallback starting point.
     */
    private fun requestAutostartPermission() {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val candidates = when {
            manufacturer in setOf("xiaomi", "poco", "redmi") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity2"
            )
            manufacturer in setOf("huawei", "honor") -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            manufacturer in setOf("oppo", "realme", "oneplus") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
            manufacturer == "vivo" -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
            manufacturer == "asus" -> listOf("com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity")
            else -> emptyList()
        }
        for ((pkg, cls) in candidates) {
            try {
                startActivity(android.content.Intent().apply {
                    component = android.content.ComponentName(pkg, cls)
                })
                return
            } catch (e: Exception) { /* try next candidate */ }
        }
        // Nothing worked (unknown ROM version, component renamed, ...) — the app's own settings
        // page at least gets the user one tap away from the right area on most ROMs.
        try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${requireContext().packageName}")
            ))
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_autostart_failed), android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_autostart_failed), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = requireContext().getSystemService(android.os.PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${requireContext().packageName}")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEM ROMs (or a Play-distributed build's policy restrictions) can refuse this
            // intent — fall back to the general battery-settings screen so the user can still
            // find the per-app battery option manually.
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                android.widget.Toast.makeText(requireContext(), getString(R.string.settings_battery_optimization_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after coming back from the system battery settings (or from granting the
        // notification permission via the system dialog on some ROMs) so the hint disappears
        // as soon as it's no longer needed, without requiring the user to leave and re-open
        // this screen.
        if (_binding != null) updateBatteryOptimizationUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
