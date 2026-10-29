package com.webuntis.dashboard

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.webuntis.dashboard.api.AppForegroundEvents
import com.webuntis.dashboard.api.ChangeSnapshot
import com.webuntis.dashboard.api.UpdateGate
import com.webuntis.dashboard.api.UpdateManager
import com.webuntis.dashboard.databinding.ActivityMainBinding
import com.webuntis.dashboard.ui.login.LoginViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import javax.inject.Inject

data class NavItem(val destinationId: Int, val iconRes: Int, val labelRes: Int)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val loginViewModel: LoginViewModel by viewModels()

    @Inject
    lateinit var updateManager: UpdateManager

    @Inject
    lateinit var appForegroundEvents: AppForegroundEvents

    @Inject
    lateinit var updateGate: UpdateGate

    private val navItems = listOf(
        NavItem(R.id.timetableFragment,  R.drawable.ic_calendar, R.string.nav_timetable),
        NavItem(R.id.lessonContentFragment, R.drawable.ic_lesson_content, R.string.nav_lesson_content),
        NavItem(R.id.homeworkFragment,   R.drawable.ic_homework,  R.string.nav_homework),
        NavItem(R.id.messagesFragment,   R.drawable.ic_message,   R.string.nav_messages),
        NavItem(R.id.absencesFragment,   R.drawable.ic_absence,   R.string.nav_absences),
        NavItem(R.id.eventsFragment,     R.drawable.ic_event,     R.string.nav_events),
        NavItem(R.id.classbookFragment,  R.drawable.ic_book,      R.string.nav_classbook),
        NavItem(R.id.settingsFragment,   R.drawable.ic_settings,  R.string.nav_settings),
    )

    private val tabViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            binding.bottomNavContainer.updatePadding(bottom = bars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        buildTabs()
        // Cold start from a notification tap (not on recreate: the action is consumed then).
        if (savedInstanceState == null) handleLaunchIntent(intent)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isLoginScreen = destination.id == R.id.loginFragment
            binding.bottomNavContainer.visibility = if (isLoginScreen) View.GONE else View.VISIBLE
            if (!isLoginScreen) updateSelectedTab(destination.id)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loginViewModel.isLoggedIn.collect { loggedIn ->
                    // Nothing navigates (and with it nothing opens or logs in) before the
                    // start-up update check has finished.
                    updateGate.isOpen.first { it }
                    val current = navController.currentDestination?.id
                    if (loggedIn && current == R.id.loginFragment) {
                        navController.navigate(R.id.action_login_to_timetable)
                    } else if (!loggedIn && current != R.id.loginFragment) {
                        navController.navigate(R.id.loginFragment)
                    }
                    // Only meaningful once actually past the login screen — see
                    // checkAndShowUnseenChanges() for why this can't just run unconditionally
                    // right after onCreate (the login-check navigation above hasn't settled yet
                    // at that point, even when the user is already logged in).
                    if (loggedIn) checkAndShowUnseenChanges()
                    // The start overlay hid the login screen while the app decided where to
                    // go; posted so the target screen is attached before the cover comes off.
                    binding.root.post {
                        binding.updateGateOverlay.visibility = View.GONE
                        if (loggedIn) consumePendingOpenRecentChanges()
                    }
                }
            }
        }

        // Update check on app launch — BEFORE anything logs in or opens a data screen.
        runUpdateGate()

        // In-app fallback for missed changes: WebUntisApp already kicks an immediate
        // PlanChangeCheckWorker run on cold start / returning from the background (see
        // ForegroundRefreshTracker), but that only helps if the OS actually lets the system
        // notification through — some ROMs (MIUI/Poco and similar aggressive battery managers
        // in particular) can suppress it even with every permission granted. This instead reads
        // the same persisted result directly and, if there's anything the user hasn't seen yet,
        // opens the "Neuigkeiten" dialog itself — independent of whether a system notification
        // ever showed up. The isLoggedIn collector above already covers the cold-start case (a
        // background run may have recorded something before this launch); this covers a few
        // seconds after each subsequent foreground-resume event, giving the just-kicked-off
        // check time to actually finish.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appForegroundEvents.onForegroundResume.collect {
                    delay(4000)
                    checkAndShowUnseenChanges()
                }
            }
        }
    }

    /**
     * Shows the "Neuigkeiten" dialog (RecentChangesDialogFragment) if the persisted
     * ChangeSnapshot has entries newer than SessionManager.changesLastViewedAt — i.e. changes
     * the background worker already found and recorded, but the user hasn't looked at yet.
     * Safe to call often: it's a no-op when there's nothing new, when the login screen is
     * showing, or when the dialog is already open.
     */
    private fun checkAndShowUnseenChanges() {
        if (!::navController.isInitialized) return
        val current = navController.currentDestination?.id
        if (current == null || current == R.id.loginFragment || current == R.id.recentChangesDialogFragment) return

        val sessionManager = loginViewModel.sessionManager
        val json = sessionManager.lastNotifiedSnapshot ?: return
        val snapshot = ChangeSnapshot.parse(json) ?: return
        val hasUnseen = snapshot.recentChanges.any { it.timestampMs > sessionManager.changesLastViewedAt }
        if (hasUnseen) navController.navigate(R.id.recentChangesDialogFragment)
    }

    /**
     * Start-up sequence: update check first, login (and the screens behind it) afterwards.
     *
     * With a connection (and an updater-enabled build) the check runs under a full-screen
     * overlay. If an update exists the user is asked right away; the app only continues once
     * they declined, the install was handed over to the system, or they skipped the download.
     * Without a connection, in F-Droid builds, on a failed/slow check (timeout) or when already
     * up to date the gate opens immediately and the app starts as usual. Reason for the order: a
     * crash after login (which the update might fix) used to hit before the update dialog could
     * ever show — see [UpdateGate].
     */
    private fun runUpdateGate() {
        if (updateGate.isOpen.value) return   // already done in this process (e.g. rotation)
        if (!BuildConfig.SELF_UPDATE || !hasInternetConnection()) {
            // No check needed. With stored credentials the app skips the login mask: keep it
            // covered until the isLoggedIn collector has navigated on (it removes the overlay).
            if (loginViewModel.hasStoredLogin) showUpdateGate(null, skippable = false)
            updateGate.open()
            return
        }
        showUpdateGate(R.string.settings_update_checking, skippable = false)
        lifecycleScope.launch {
            try {
                val info = withTimeoutOrNull(UPDATE_CHECK_TIMEOUT_MS) { updateManager.checkForUpdates().getOrNull() }
                val url = info?.downloadUrl
                if (info != null && info.hasUpdate && url != null && askInstallUpdate(info)) {
                    showUpdateGate(R.string.update_gate_downloading, skippable = true)
                    awaitDownloadAndInstall(url, "webuntis-dashboard-${info.latestVersion}.apk")
                }
            } catch (e: CancellationException) {
                // Activity went away mid-check: leave the gate closed, the next start re-checks.
                throw e
            } catch (e: Exception) {
                // Never let the update check itself block the app.
            }
            // Stored credentials → the isLoggedIn collector takes the overlay down once it has
            // navigated past the login screen; otherwise the login mask is the next screen.
            if (!loginViewModel.hasStoredLogin) binding.updateGateOverlay.visibility = View.GONE
            updateGate.open()
        }
    }

    private fun showUpdateGate(statusRes: Int?, skippable: Boolean) {
        binding.updateGateOverlay.visibility = View.VISIBLE
        if (statusRes != null) binding.updateGateStatus.setText(statusRes) else binding.updateGateStatus.text = ""
        binding.updateGateSkip.visibility = if (skippable) View.VISIBLE else View.GONE
    }

    private fun hasInternetConnection(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** true = install now, false = later. The dialog is not cancellable: one of the two must be chosen. */
    private suspend fun askInstallUpdate(info: UpdateManager.UpdateInfo): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.settings_update_dialog_title_main))
                .setMessage(getString(R.string.settings_update_dialog_message_main, info.latestVersion, info.releaseNotes ?: ""))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.settings_update_dialog_install)) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(getString(R.string.settings_update_dialog_later)) { _, _ -> if (cont.isActive) cont.resume(false) }
                .show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }

    /** Returns once the installer was launched, the download couldn't start, or the user skipped. */
    private suspend fun awaitDownloadAndInstall(url: String, fileName: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            binding.updateGateSkip.setOnClickListener { if (cont.isActive) cont.resume(Unit) }
            updateManager.downloadAndInstall(url, fileName) { if (cont.isActive) cont.resume(Unit) }
        }
    }

    private fun buildTabs() {
        val container = binding.navTabs
        container.removeAllViews()
        tabViews.clear()

        val displayWidth = resources.displayMetrics.widthPixels
        val minTabWidth = (72 * resources.displayMetrics.density).toInt()
        val tabWidth = maxOf(displayWidth / navItems.size, minTabWidth)

        navItems.forEach { item ->
            val tab = LayoutInflater.from(this)
                .inflate(R.layout.item_nav_tab, container, false)
            tab.layoutParams = LinearLayout.LayoutParams(tabWidth, LinearLayout.LayoutParams.MATCH_PARENT)

            tab.findViewById<ImageView>(R.id.tab_icon).setImageResource(item.iconRes)
            tab.findViewById<TextView>(R.id.tab_label).text = getString(item.labelRes)

            tab.setOnClickListener {
                if (navController.currentDestination?.id != item.destinationId) {
                    navController.navigate(item.destinationId)
                }
            }
            container.addView(tab)
            tabViews.add(tab)
        }
    }

    private fun updateSelectedTab(destinationId: Int) {
        val activeColor   = getColorFromAttr(androidx.appcompat.R.attr.colorPrimary)
        val inactiveColor = getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)

        navItems.forEachIndexed { i, item ->
            val tab     = tabViews[i]
            val icon    = tab.findViewById<ImageView>(R.id.tab_icon)
            val label   = tab.findViewById<TextView>(R.id.tab_label)
            val selected = item.destinationId == destinationId
            val color   = if (selected) activeColor else inactiveColor
            icon.setColorFilter(color)
            label.setTextColor(color)

            if (selected) {
                binding.bottomNav.post {
                    val scrollX = tab.left - (binding.bottomNav.width - tab.width) / 2
                    binding.bottomNav.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
                }
            }
        }
    }

    private fun getColorFromAttr(attr: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    companion object {
        /** Tap on a notification: open [EXTRA_TARGET] once logged in. */
        const val ACTION_OPEN_FROM_NOTIFICATION = "com.webuntis.dashboard.action.OPEN_FROM_NOTIFICATION"
        const val EXTRA_TARGET = "notification_target"
        const val TARGET_CHANGES   = "changes"    // "Neuigkeiten" dialog (lesson changes)
        const val TARGET_MESSAGES  = "messages"
        const val TARGET_HOMEWORK  = "homework"
        const val TARGET_CLASSBOOK = "classbook"
        const val TARGET_EVENTS    = "events"

        /** Longest the start waits for the update check before just continuing. */
        private const val UPDATE_CHECK_TIMEOUT_MS = 5_000L
    }

    /** Set by a tapped notification; consumed as soon as it's safe to navigate. */
    private var pendingTarget: String? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
        consumePendingOpenRecentChanges()
    }

    private fun handleLaunchIntent(intent: android.content.Intent?) {
        if (intent?.action == ACTION_OPEN_FROM_NOTIFICATION) {
            pendingTarget = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_CHANGES
            // Consumed: a later recreate (rotation, process restore) must not navigate again.
            intent.action = android.content.Intent.ACTION_MAIN
        }
    }

    /**
     * Opens what a tapped notification points at — the matching tab (messages, homework,
     * classbook), or the timetable tab plus the "Neuigkeiten" dialog for lesson changes — but only on top of the real
     * app: never while the login screen is showing (the login collector retries after navigating
     * on) and not before the start-up update check has finished.
     */
    private fun consumePendingOpenRecentChanges() {
        val target = pendingTarget ?: return
        if (!::navController.isInitialized) return
        if (!updateGate.isOpen.value) return
        val current = navController.currentDestination?.id ?: return
        if (current == R.id.loginFragment) return
        pendingTarget = null
        if (target == TARGET_CHANGES) {
            // Lesson changes: timetable tab underneath, "Neuigkeiten" dialog on top — so closing
            // the dialog leaves the user on the timetable, whatever tab was open before.
            if (current == R.id.recentChangesDialogFragment) return
            if (current != R.id.timetableFragment) navController.navigate(R.id.timetableFragment)
            navController.navigate(R.id.recentChangesDialogFragment)
            return
        }
        val destination = when (target) {
            TARGET_MESSAGES  -> R.id.messagesFragment
            TARGET_HOMEWORK  -> R.id.homeworkFragment
            TARGET_EVENTS    -> R.id.eventsFragment
            else             -> R.id.classbookFragment
        }
        if (current != destination) navController.navigate(destination)
    }
}
